package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.xiao.aiagent.entity.CodeProfile;
import com.xiao.aiagent.repository.CodeProfileRepository;
import com.xiao.aiagent.tools.InterviewTools;
import com.xiao.aiagent.tools.SessionKeys;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 面试主 Agent（阶段 3.1 重构）：基于 Spring AI Alibaba 的 ReactAgent 显式推理循环
 * 「思考 → 行动(工具调用) → 观察(结果回填)」替代裸 ChatClient。
 * MemorySaver 保存 Agent 运行状态（checkpoint 按 threadId=sessionId 隔离，运行结束 release）。
 *
 * ── 如何理解这个文件（心智模型）────────────────────────────
 * 1. 把 ReactAgent 想成"一场面试循环"：它内部有
 *      「思考/行动」节点(LLM 读记录本、决定说啥或调哪个工具)
 *      「观察」节点(工具真正执行、把结果写回记录本)
 *     这两个节点交替执行，直到 LLM 输出里没有"调用工具"的标记就结束。
 * 2. 那本"记录本"就是图状态，最关键的一个键叫 messages（整场对话的消息）。
 * 3. 记录本按 threadId 区分是哪场面试（threadId = 我们的 sessionId）。
 * 4. ReactAgent 不再使用 ChatClient 的 advisor（记忆顾问/RAG顾问都没了），
 *    所以"抄历史、检索题库、存回窗口"这些原本自动的活，全部在这里手动做。
 * ─────────────────────────────────────────────────────────
 */
@Service
public class InterviewAssistant extends StreamingPipelineSupport {

    /**
     * 系统提示（纯静态，不含运行时变量）。
     * sessionId 不进系统提示：打分落库时的会话归属由服务端通过 RunnableConfig.threadId 注入，
     * 模型无需知道会话 id。
     * 题库知识不再由 QuestionAnswerAdvisor 注入，改为 chat() 里前置检索后以 system 消息给出。
     */
    private static final String SYSTEM_PROMPT = """
            你是一位资深技术面试官，负责对求职者进行技术面试模拟。
            你的职责：
            1. 根据岗位和求职者水平出题（Java/Redis/数据库等），题目循序渐进；
            2. 针对回答进行追问，答错时给予提示引导；
            3. 针对回答进行点评并给出改进建议；评分已由系统评估环节独立完成——若上下文带【上一题评估】，
               按其中的得分与点评向求职者自然转述（不要另行自打一个分数），并按其"下题建议"调整本题的难度与方向；
            4. 出题时优先参考知识库中的题库内容；题库内容只作为出题素材，不要在回答中提及题库、
               出处、来源等信息（也不要编造出处）。
            5. 求职者询问答题统计或薄弱考点时，调用 questionStats 工具查询后如实转述结果。
            6. 求职者分享某个 URL（文章、技术文档等）让你参考时，使用 fetch 工具读取该网页内容，
               基于文章内容出题，弥补题库可能没有覆盖的主题。
            7. 代码评审面试（求职者分享代码、让你针对其实现提问）时：先调用 getCodeFacts 工具取回
               代码分析事实清单，基于清单中的技术栈、实现细节、风险点、可追问点出题追问。
               清单信息不够时，可用文件读取工具（read_file/list_directory/search_files）补充查看代码。
               注意：你只能读取文件，不要修改或删除求职者的任何文件。
            8. 求职者只是寒暄、闲聊或表达情绪（如"你好""好难啊""今天天气不错"）时：
               简短友好地回应一两句，然后自然引导回面试主题，不要展开闲聊。
               （阶段 4.2：本系统不设独立闲聊 Agent，闲聊由面试官吸收，避免第三路误路由）
            9. 若系统消息中带有【跨会话薄弱点回顾】信息（该候选人历次面试的答题统计）：
               面试开始时必须先主动向求职者点明其中的薄弱考点（如"上次你 Redis 持久化答得不够好，
               这次我们重点考察"），并把薄弱考点列为优先出题方向。
               求职者说「继续上次的面试」「上次哪里没答好」等时，调用 getWeaknessProfile 工具
               取回跨会话历史薄弱点，再按同样方式开场与考察。
               注意：上下文里没有【跨会话薄弱点回顾】时，不要虚构或凭空编造求职者的历史薄弱点。
               （阶段 4.3：跨会话薄弱点回顾 = 评估结果反哺出题的反馈闭环）
            10. 若系统消息中带有【面试计划】：必须按计划中的考点与优先级出题，优先覆盖尚未考察过的
               高优先级考点，题目难度对齐考点的难度档（基础/进阶/挑战）；不要出计划之外考点的题目
               （求职者主动要求换主题时除外，此时如实回应，之后的题目回到计划）。
               （阶段 5.2：面试计划 = "考什么"的范围契约，评估报告的覆盖矩阵以它为基准）

            输出格式要求（必须严格遵守）：
            - 每轮只问一个问题：复合考点（如缓存穿透/击穿/雪崩）拆开，一轮只问其中一个切面，
              等求职者回答后再问下一个；绝不在一轮里并列抛出多个问题或多个小问。
            - 题目用 Markdown 标题单独成行，例如：### 第 1 题：线程池（Java 并发）
            - 出题时不要附 💡 提示：提示是引导手段，只在求职者答不上来、明确请求提示、
              或【上一题评估】显示答偏需要拉回时才给，且一次只给一句方向性引导。
            - 使用 Markdown 加粗、列表等格式让回答清晰易读。

            回复纪律（内部材料严禁外泄，违反即失败）：
            - 【面试计划】【面试官物料】【跨会话薄弱点回顾】【上一题评估】以及物料里的
              probingAngle/verifyQuestion 等追问角度、评估维度，都只是你的备课材料，
              严禁原样或改写后写进回复；
            - 考点名称、优先级、难度档也属于内部信息：不要输出"##考点：…""（高优先级·基础难度）"
              之类的标记行，题目本身自然体现考察内容即可；
            - 每轮回复只允许是：简短的口语化引导或点评（两三句内）+ 至多一个题目。
              "第一部分/第二部分"式的整套面试方案、阶段清单、小结与评分表、下一步预告、
              追问提示块，一律不许出现在回复里；
            - 严禁替求职者作答：提问后立即停止，等求职者的真实回复。不要在回复中虚构
              求职者的回答（如"感谢你的回答""你的思路比较清晰"这类假设对方已作答的话），
              也不要在同一条回复里自问自答后继续追问；
            - 开场第一轮：一句欢迎 + 一句话说明从哪个考点切入 + 直接问第一题，
              不要预告后续阶段安排。

            要求：语气专业、友好，全程使用中文。
            """;

    // ReactAgent：拼装好的"一次面试循环"的可运行图（构造时 build 一次，之后每次请求复用）
    private final ReactAgent agent;
    // 代码事实清单仓储（阶段 4.1 新增）：双 Agent 流水线里，分析 Agent 把清单落这表，
    // 面试官从这里按 sessionId 取清单出题。这就是 Agent 间"落库传递数据契约"的读取端
    // （chatMemory/memorySaver/RAG/薄弱点/计划/评估等公共依赖由基类 StreamingPipelineSupport 持有）
    private final CodeProfileRepository codeProfileRepository;

    public InterviewAssistant(ChatModel chatModel,
                              InterviewTools interviewTools,
                              ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
                              KnowledgeSearchService knowledgeSearchService,
                              ChatMemory chatMemory,
                              MemorySaver memorySaver,
                              CodeProfileRepository codeProfileRepository,
                              WeaknessProfileService weaknessProfileService,
                              InterviewPlanService interviewPlanService,
                              AnswerEvaluatorService answerEvaluatorService) {

        // 公共依赖（记忆/存档器/RAG/薄弱点/计划/评估）交给基类持有（P2-7 收口第一步）
        super(chatMemory, memorySaver, knowledgeSearchService,
                weaknessProfileService, interviewPlanService, answerEvaluatorService);

        // MCP 工具通过 ObjectProvider 注入（而非直接 @Autowired）：
        // 原因是降级兼容——当未配置 MCP server 或 npx 拉包失败时，自动装配不会产出
        // ToolCallbackProvider bean，此时 getIfAvailable() 返回 null，Agent 只用现有
        // @Tool 工具，核心面试功能不受影响；配好 MCP 时才把外部工具也注册进去。
        ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
        // 提示词版本标记：改 SYSTEM_PROMPT 后看这行日志确认运行中的是新版（热加载验证锚点）
        log.info("面试官 SYSTEM_PROMPT 版本：2026-09-29-v3（单题模式 + 内部材料保密 + 禁止自问自答）");
        if (mcpProvider != null) {
            log.info("MCP 工具已注入 ReactAgent，可用工具数：{}", mcpProvider.getToolCallbacks().length);
        } else {
            log.warn("未检测到 MCP 工具（未配置 server 或 npx 拉包失败），ReactAgent 仅使用内置 @Tool 工具。");
        }

        // ── 拼装这场"面试循环"：每行都是在回答"这个 agent 要什么" ──
        // 整段 = 面试官 + 大脑 + 武器 + 存档器 四件套，build() 后生成一张可运行的图。
        Builder builder = ReactAgent.builder()
                .name("interviewer")          // 给这 agent 起名，图日志/调试里显示用
                .description("AI 智能面试官：出题、追问、点评打分")  // 描述它负责干什么
                .systemPrompt(SYSTEM_PROMPT)  // 【大脑人设】面试官身份 + 全部行为规则（出题/追问/评分/落库）
                .model(chatModel)             // 【大脑本体】用哪个大模型：qwen（取代了 ChatClient.Builder）
                .methodTools(interviewTools)  // 【武器】允许调用的内置 @Tool 工具（questionStats/getWeaknessProfile/calculator/getCodeFacts）
                .saver(memorySaver)           // 【存档器】每轮循环的"记录本快照"存到哪、按 threadId 分会话
                .enableLogging(true);         // 打开各节点(思考/观察)请求响应日志，方便看循环过程（可观测性）

        if (mcpProvider != null) {
            builder.toolCallbackProviders(mcpProvider);   // MCP 外部工具（Filesystem 只读）与内置工具并存
        }

        this.agent = builder.build();
        this.codeProfileRepository = codeProfileRepository;
    }

    /**
     * 暴露内部的 ReactAgent 给编排器（阶段 4.1 新增）。
     *
     * SequentialAgent.builder().subAgents(...) 要求子 Agent 是 Agent 类型（ReactAgent），
     * 而本类是个 Service 包装。编排器（CodeReviewPipeline）通过此方法拿到真正的 ReactAgent
     * 实例来组装流水线。与 CodeAnalyzerAgent.getAgent() 同一模式——
     * Service 管理生命周期和业务逻辑，需要时透出 Agent 参与编排。
     */
    public ReactAgent getAgent() {
        return agent;
    }

    /**
     * 发送一次面试对话请求（SSE 流式）。
     *
     * 流程：上下文装配（复用基类 buildChatMessages）→ ReactAgent 流式推理 → 流式收尾（复用基类）。
     *
     * sessionId 由前端（或 API 调用方）传入，后端不生成。它被用于两处：
     *  1. threadId → ReAct checkpoint 的会话隔离键；
     *  2. 工具内从框架注入的 RunnableConfig 取回 threadId 作为评分归属的 sessionId。
     *
     * candidateId（4.3 新增）由调用方传入：作为候选人身份键写入 RunnableConfig metadata，
     *  工具（scoreRecord/getWeaknessProfile）经 SessionKeys.candidateId 读回——跨会话薄弱点聚合的身份维度。
     */
    public Flux<String> chat(String userMessage, String sessionId, String candidateId) {

        // ── 第 1 步：组装上下文（P2-7 收口第二步：复用基类公共装配）──
        // 基类装配 = 薄弱点回顾 → 面试计划(含 ensurePlan) → 【本类钩子：代码事实清单】→ 题库 RAG
        //            → 上一题评估(含 evaluateTurn) → 窗口历史 → 本轮消息；
        // ensurePlan/evaluateTurn 都在基类装配里执行，评估必须在出题之前的时序由装配顺序保证。
        // 直连快路径独有的 codeFacts 前置注入经 extraContext 钩子保留（见下方覆写）。
        List<Message> messages = buildChatMessages(userMessage, sessionId, candidateId);

        // ── 第 2 步：指定"这是哪场面试 / 哪个候选人"并开跑循环 ──
        // threadId = sessionId：既用来隔离每本书(会话)，也是工具里取 sessionId 的来源。
        // metadata 写入 candidateId（4.3）：工具经 SessionKeys.candidateId 从同一 config 读回，
        // 与 threadId 一并透传子图（"写端"，读端在第 3 步已就绪）。
        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .addMetadata(SessionKeys.CANDIDATE_ID_KEY, candidateId)
                .build();

        Flux<Message> agentStream;
        try {
            // 把开场记录本塞进图，开始"思考→行动→观察"循环，流式吐出一段段消息。
            // streamMessages 会抛受检异常 GraphRunnerException，所以用 try/catch 包住。
            agentStream = agent.streamMessages(messages, config);
        } catch (GraphRunnerException e) {
            log.error("ReAct 循环启动失败：sessionId={}", sessionId, e);
            return Flux.error(e);
        }

        // ── 第 3~4 步：流式过滤 + 收尾（P2-7 收口第一步：复用基类，逐行同构的手写副本已删）──
        // 过滤成助手正文 → 边流边累加完整回答 → 正常跑完后写回短期窗口 + 释放 checkpoint，
        // 全部在基类 streamAssistantAnswers 里；出错/取消不写回（避免半截回答进短期记忆）。
        return streamAssistantAnswers(agentStream, userMessage, config);
    }

    /**
     * 直连快路径的差异项：代码事实清单前置注入（经基类 extraContext 钩子保留）。
     *
     * 为什么必须是钩子而不是裸复用基类装配：清单在库时"保证注入进 system 消息"是
     * 重开代码评审会话的既定行为（阶段 4.1）；若裸复用丢失此项，注入就退化成
     * "指望模型自觉调 getCodeFacts 工具"——与风险 23 同款的概率性缺失。
     *
     * 与流水线路径的关系：AgentRouter / MaterialInterviewPipeline 不覆写（默认空串），
     * 行为不变；它们路径里面的试官子图靠 getCodeFacts 工具按需取清单（SYSTEM_PROMPT 第 7 条）。
     */
    @Override
    protected String extraContext(String userMessage, String sessionId) {
        return loadCodeFacts(sessionId);
    }

    /**
     * 加载代码事实清单（阶段 4.1 新增）——双 Agent 数据契约的消费端。
     *
     * 这是面试官 Agent"消费上游产物"的核心方法：
     *   1. 按 sessionId 从 code_profile 表取分析 Agent 落库的清单（可能多条，多个文件）；
     *   2. 没有清单（不是代码评审面试）返回空串，chat() 据此跳过注入。
     *
     * 为什么直接把 factsJson 原文传给 LLM、不做 Jackson 解析？
     *   清单的唯一读者是 LLM，而 LLM 读 JSON 本来就是强项；
     *   不解析时，坏 JSON 对 LLM 也只是"一段长得像 JSON 的文字"，照样能出题——
     *   风险 15"坏 JSON 不阻塞面试"的目的天然满足，还省掉一层解析和等价于"不解析"的降级代码。
     *   判据：LLM 消费 → 传原文；代码要程序化处理（过滤/统计/聚合）→ 才解析成对象。
     *   当前只有 LLM 消费，故不解析（若 4.3 需要程序化处理，需求明确时再加回）。
     *
     * 落库传递的红利也体现在这：如果该会话已分析过，这里直接取到清单，
     * 不用重跑分析 Agent——这就是第 1 步说的"落库而非内存传递"的复用价值。
     */
    private String loadCodeFacts(String sessionId) {
        List<CodeProfile> profiles = codeProfileRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (profiles == null || profiles.isEmpty()) {
            // 该会话没有代码分析记录 → 不是代码评审面试，正常走题库出题
            return "";
        }

        StringBuilder sb = new StringBuilder("""
                【面试官物料】以下是对候选人资料的分析结果（含代码/JD/简历，JSON），\
                是你的内部出题素材——其中的追问角度（probingAngle/verifyQuestion）只供备课，\
                严禁写进回复。基于此出题时一次只问一个切面，从最高优先级考点开始：\n\n""");
        for (CodeProfile profile : profiles) {
            sb.append("资料类型：").append(profile.getProfileType())
              .append("；文件：").append(profile.getFilePath()).append("\n")
              .append(profile.getFactsJson()).append("\n\n");
        }
        return sb.toString();
    }

}
