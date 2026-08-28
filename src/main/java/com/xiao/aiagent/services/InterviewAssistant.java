package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.xiao.aiagent.entity.CodeProfile;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.repository.CodeProfileRepository;
import com.xiao.aiagent.tools.InterviewTools;
import com.xiao.aiagent.tools.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
public class InterviewAssistant {

    private static final Logger log = LoggerFactory.getLogger(InterviewAssistant.class);

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
            4. 出题时优先参考知识库中的题库内容，引用时注明出处。
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
            - 题目用 Markdown 标题单独成行，例如：### 第 1 题：线程池（Java 并发）
            - 题目出处单独成段并用引用格式：> 📚 出处：面试题库-XX模块
            - 提示单独成段：💡 提示：...
            - 不同部分（题目/出处/提示/你的点评）之间必须空一行，不要连写在一起。
            - 使用 Markdown 加粗、列表等格式让回答清晰易读。

            要求：语气专业、友好，全程使用中文。
            """;

    // ReactAgent：拼装好的"一次面试循环"的可运行图（构造时 build 一次，之后每次请求复用）
    private final ReactAgent agent;
    // 短期记忆：Redis 滑动窗口（60 条对话）。ReactAgent 不带记忆 advisor，
    // 所以"取历史 / 存回窗口"都靠我们在这类里手动调它（见 chat() 第 1、4 步）
    private final ChatMemory chatMemory;
    // Agent 运行状态保存器：每轮循环的"记录本快照"按 threadId 隔离。
    // 每轮跑完会 release() 释放，防止下轮把上一轮整本记录（旧消息）重复带进上下文
    private final MemorySaver memorySaver;
    // 题库知识检索（阶段 4.2 重构）：RAG 前置检索收敛到 KnowledgeSearchService（Rule of Three 抽公共组件）
    private final KnowledgeSearchService knowledgeSearchService;
    // 代码事实清单仓储（阶段 4.1 新增）：双 Agent 流水线里，分析 Agent 把清单落这表，
    // 面试官从这里按 sessionId 取清单出题。这就是 Agent 间"落库传递数据契约"的读取端
    private final CodeProfileRepository codeProfileRepository;
    // 跨会话薄弱点回顾（4.3 新增）：开场注入候选人历史薄弱点，实现"反馈闭环"的消费端
    private final WeaknessProfileService weaknessProfileService;
    // 面试计划（阶段 5.2 新增）："考什么"的范围契约，chat() 每轮注入 + 评估官的考点词表来源
    private final InterviewPlanService interviewPlanService;
    // 答案评估（阶段 5.3 新增）：会话内评估闭环——每轮评分落库 + 产【上一题评估】注入文本
    private final AnswerEvaluatorService answerEvaluatorService;

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

        // MCP 工具通过 ObjectProvider 注入（而非直接 @Autowired）：
        // 原因是降级兼容——当未配置 MCP server 或 npx 拉包失败时，自动装配不会产出
        // ToolCallbackProvider bean，此时 getIfAvailable() 返回 null，Agent 只用现有
        // @Tool 工具，核心面试功能不受影响；配好 MCP 时才把外部工具也注册进去。
        ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
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
        this.chatMemory = chatMemory;
        this.memorySaver = memorySaver;
        this.knowledgeSearchService = knowledgeSearchService;
        this.codeProfileRepository = codeProfileRepository;
        this.weaknessProfileService = weaknessProfileService;
        this.interviewPlanService = interviewPlanService;
        this.answerEvaluatorService = answerEvaluatorService;
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
     * 流程：跨会话薄弱点注入 + 题库 RAG 前置检索 + Redis 窗口历史组装消息 → ReactAgent 流式推理
     * → 完成后写回短期窗口并释放本次运行的 checkpoint。
     *
     * sessionId 由前端（或 API 调用方）传入，后端不生成。它被用于两处：
     *  1. threadId → ReAct checkpoint 的会话隔离键；
     *  2. 工具内从框架注入的 RunnableConfig 取回 threadId 作为评分归属的 sessionId。
     *
     * candidateId（4.3 新增）由调用方传入：作为候选人身份键写入 RunnableConfig metadata，
     *  工具（scoreRecord/getWeaknessProfile）经 SessionKeys.candidateId 读回——跨会话薄弱点聚合的身份维度。
     */
    public Flux<String> chat(String userMessage, String sessionId, String candidateId) {

        // ── 第 0 步（阶段 5.3）：会话内评估闭环——先评估上一答，再组装本题上下文 ──
        // 评估必须在出题【之前】：它的结论（得分/薄弱/下题建议）要影响本题的难度与方向。
        // 评分落库由评估服务代码直接执行（不再经面试官调 scoreRecord 工具，结构性修掉风险 23）。
        InterviewPlan plan = interviewPlanService.ensurePlan(userMessage, sessionId, candidateId);
        AnswerEvaluatorService.EvaluationOutcome evaluation =
                answerEvaluatorService.evaluateTurn(userMessage, sessionId, candidateId, plan);

        // ── 第 1 步：给"这场面试"准备好开场记录本（图状态 messages 的初始内容）──
        // 这里在模拟以前记忆顾问 + RAG 顾问自动做的事，只是现在由我们显式组装。
        // 阶段 4.1 新增：还可能注入【代码事实清单】——由上游 CodeAnalyzerAgent 产出、
        // 落库在 code_profile 表，面试官据此出题（这是双 Agent 数据契约的消费端）；
        // 阶段 4.3 新增：【跨会话薄弱点回顾】——候选人有历史评分时注入，反馈闭环的消费端；
        // 阶段 5.2 新增：【面试计划】——"考什么"的范围契约；5.3 新增：【上一题评估】——当轮反馈。
        //   ① 跨会话薄弱点回顾（WeaknessProfileService，4.3）→ system 消息（无历史则跳过）；
        //   ② 面试计划（InterviewPlanService，5.2）→ system 消息（无计划/生成失败跳过）；
        //   ③ 代码事实清单（loadCodeFacts 取自 code_profile 表）→ system 消息；
        //   ④ 题库知识（knowledgeSearchService 检索结果）→ system 消息；
        //   ⑤ 上一题评估（AnswerEvaluatorService，5.3）→ system 消息（非作答/评估失败跳过）；
        //   ⑥ 短期窗口历史（chatMemory.get）→ 最近 60 条对话抄进来；
        //   ⑦ 最后放上面试者这句新提问。
        List<Message> messages = new ArrayList<>();

        String weaknessContext = weaknessProfileService.loadProfile(candidateId);
        if (!weaknessContext.isBlank()) {
            messages.add(new SystemMessage(weaknessContext));
        }

        String planContext = interviewPlanService.renderPlan(plan);
        if (!planContext.isBlank()) {
            messages.add(new SystemMessage(planContext));
        }

        String codeFactsContext = loadCodeFacts(sessionId);
        if (!codeFactsContext.isBlank()) {
            messages.add(new SystemMessage(codeFactsContext));
        }

        String knowledgeContext = knowledgeSearchService.search(userMessage);
        if (!knowledgeContext.isBlank()) {
            messages.add(new SystemMessage(knowledgeContext));
        }

        if (!evaluation.injectionText().isBlank()) {
            messages.add(new SystemMessage(evaluation.injectionText()));
        }

        messages.addAll(chatMemory.get(sessionId));          // Redis 滑动窗口历史
        messages.add(new UserMessage(userMessage));

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

        // ── 第 3 步：把图吐出的消息流，过滤成"助手说的文字"给前端（SSE 打字机）──
        // 图会吐出多种消息（可能含工具调用的中间消息），只要 AssistantMessage 的正文文本。
        Flux<String> answer = agentStream
                .filter(m -> m instanceof AssistantMessage)
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank());

        AtomicReference<String> fullAnswer = new AtomicReference<>("");

        // ── 第 4 步：返回流 + 收尾 ──
        return answer
                .doOnNext(chunk -> fullAnswer.set(fullAnswer.get() + chunk))  // 边流边累加出完整回答
                .doFinally(signal -> {                                       // 流结束后统一收尾
                    if (signal == SignalType.ON_COMPLETE) {                  // 只有"正常跑完"才算数
                        // 4a. 把"面试者的话 + 完整回答"写回 Redis 短期窗口（MessageWindowChatMemory 自动淘汰最旧）。
                        //     由于没有记忆 advisor 了，这一步必须手写，否则下轮短期记忆失效。
                        chatMemory.add(sessionId, List.of(
                                new UserMessage(userMessage),
                                new AssistantMessage(fullAnswer.get())));
                        // 4b. release 释放本轮"记录本快照"。
                        //     我们把历史用 Redis 窗口自己管了，若让图把整本(含旧消息)跨轮保留，
                        //     下轮会和窗口历史重复；所以跑完即释放。
                        try {
                            memorySaver.release(config);
                        } catch (Exception e) {
                            log.warn("释放 checkpoint 失败：sessionId={}, {}", sessionId, e.getMessage());
                        }
                    }
                });
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

        StringBuilder sb = new StringBuilder("【面试官物料】以下是对候选人资料的分析结果（含代码/JD/简历，JSON），请基于此出题追问：\n\n");
        for (CodeProfile profile : profiles) {
            sb.append("资料类型：").append(profile.getProfileType())
              .append("；文件：").append(profile.getFilePath()).append("\n")
              .append(profile.getFactsJson()).append("\n\n");
        }
        return sb.toString();
    }

}
