package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.xiao.aiagent.repository.CodeProfileRepository;
import com.xiao.aiagent.tools.SessionKeys;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 意图路由 Agent（阶段 4.2 新增）——多 Agent 系统的【统一入口 / 分诊台】。
 *
 * 用 LlmRoutingAgent 替换 4.1 的规则判断（CodeReviewPipeline.isCodeReviewRequest）：
 *   规则只能命中"提到代码文件"的表面特征；LLM 路由理解语义——
 *   "帮我看看上周写的那个线程池"（没路径、没扩展名）也能正确分到代码评审流水线。
 *
 * ── 如何理解这个文件（心智模型）────────────────────────────
 * 把本类想成医院的"分诊台护士"：患者（用户消息）来了，护士看一眼症状（LLM 读消息），
 * 判断该挂哪个科：
 *   - 要看代码评审                        → 分到「代码评审流水线」（4.1 双 Agent，SequentialAgent）
 *   - 要 JD+简历+代码一起综合面试          → 分到「综合面试流水线」（4.2，并行分析 → 面试官）
 *   - 其他（出题/追问/评分/统计/闲聊寒暄） → 分到「面试官」（ReactAgent）
 *   分不出/分错了                         → 一律挂「面试官」兜底（fallbackAgent），
 *                                          保证最核心的面试功能永远可用（计划书风险 16）。
 * 分诊台自己不看病：它只做一次"分派"，真正的活是子 Agent 干的。
 * ─────────────────────────────────────────────────────────
 *
 * 为什么不设独立的「闲聊 Agent」（对计划书的主动裁剪）：
 *   闲聊频率低、回答无业务价值，单独一路会带来第三路误路由风险——
 *   面试中随口一句"好难啊"被切去闲聊 Agent，面试上下文就断了。
 *   与计划书"不单独做知识 Agent"同一逻辑：主场景 Agent 能吸收的低频话题，不单独设路。
 *   代价由面试官 prompt 吸收（寒暄简短回应后引导回面试主题），路由层保持两路，简单即正确。
 *
 * LlmRoutingAgent 内部机制（读框架源码总结，配合本类注释理解）：
 *   1. 路由节点把「子 Agent 的 name + description」列给路由 LLM 当菜单；
 *   2. 路由 LLM 返回要委派的 Agent 名字列表（支持一个或多个，多个=并行委派）；
 *   3. 返回名单会校验（必须是子 Agent 之一），无效会带上错误信息重试；
 *   4. 重试后仍失败 → 使用 fallbackAgent。
 *   所以：子 Agent 的 name/description 写得好不好，直接决定路由准不准。
 *
 * 复用快路径（4.1 落库复用的保留）：
 *   同会话已分析过代码、且用户消息仍是代码评审意图 → 直接调面试官 chat()，
 *   loadCodeFacts 会前置注入清单。省一次路由 LLM 调用、也省一次重复分析——
 *   能确定答案的便宜事，不必每次都问 LLM。
 */
@Service
public class AgentRouter extends StreamingPipelineSupport {

    /**
     * 路由指令——给"分诊台 LLM"看的分类标准。
     * 注意区分三个层次：
     *   - 子 Agent 的 description：给路由 LLM 看"每个科是干嘛的"（写在各自类里）；
     *   - 这里的 instruction：给路由 LLM 看"按什么标准挑科"（本类）；
     *   - 子 Agent 的 systemPrompt：给干活 LLM 看"这个科里怎么干活"（子类里）。
     * 指令里写了"拿不准时选 interviewer"，与 fallbackAgent 形成双保险：
     *   LLM 层面倾向面试官 + 框架层面失败兜底面试官。
     */
    private static final String ROUTING_INSTRUCTION = """
            你是面试系统的意图路由器。根据用户消息的内容，从下方可用 Agent 中选择最合适的一个：
            1. 用户要求针对自己的代码做评审面试（提到代码文件、路径、实现细节、代码评审等）→ 选 code-review-pipeline；
            2. 用户要求结合 JD/岗位描述、简历、代码【综合面试】（同时提到两类以上资料）→ 选 combined-interview；
            3. 其余情况（出题、追问、点评、答题统计，以及寒暄、闲聊等）→ 选 interviewer；
            4. 拿不准时 → 选 interviewer（面试是主场景）。
            """;

    // 分诊台：构造时 build 一次，之后每次请求复用（与 4.1 pipeline 同模式）
    private final LlmRoutingAgent router;
    // 面试官（复用快路径直接调它的 chat()，loadCodeFacts 前置注入清单）
    private final InterviewAssistant interviewer;
    // 判断"该会话已分析过没"——复用快路径的开关
    private final CodeProfileRepository codeProfileRepository;
    // chatMemory / memorySaver / knowledgeSearchService 三个公共依赖由基类 StreamingPipelineSupport 持有

    public AgentRouter(ChatModel chatModel,
                       CodeReviewPipeline codeReviewPipeline,
                       CombinedInterviewPipeline combinedInterviewPipeline,
                       InterviewAssistant interviewer,
                       CodeProfileRepository codeProfileRepository,
                       ChatMemory chatMemory,
                       MemorySaver memorySaver,
                       KnowledgeSearchService knowledgeSearchService,
                       WeaknessProfileService weaknessProfileService) {

        super(chatMemory, memorySaver, knowledgeSearchService, weaknessProfileService);   // 四个公共依赖进基类

        // ── 组装"分诊台" ──
        // subAgents 里放的是"可以挂号的科室"。每个子 Agent 的 description 会被路由 LLM 看到，
        // 所以各自的 description 必须写清负责什么（这是 LLM 路由准确率的关键）。
        // fallbackAgent 传的是【名字字符串】而非对象——因为路由 LLM 返回的就是名字，
        // 框架拿这个名字去子 Agent 名单里找，兜底时同理，所以必须和 name() 完全一致。
        this.router = LlmRoutingAgent.builder()
                .name("intent-router")                                          // 分诊台自己的名字（日志可见）
                .description("意图路由器：根据用户消息意图，分派给最合适的子 Agent")  // 职责描述
                .model(chatModel)                                              // 路由决策靠 LLM 做，必须配模型
                .instruction(ROUTING_INSTRUCTION)                              // 告诉路由 LLM 分类标准
                .subAgents(List.of(
                        codeReviewPipeline.getPipeline(),        // ① 代码评审面试 → 4.1 双 Agent 流水线（SequentialAgent）
                        combinedInterviewPipeline.getPipeline(), // ② 综合面试 → 4.2 并行分析 → 面试官（SequentialAgent）
                        interviewer.getAgent()))                 // ③ 其余全部（出题/追问/评分/闲聊）→ 面试官（ReactAgent）
                .fallbackAgent("interviewer")                   // 兜底：路由失败时去面试官（主场景，风险 16）
                .saver(memorySaver)                             // checkpoint 按 threadId 隔离，跑完 release
                .build();

        this.interviewer = interviewer;
        this.codeProfileRepository = codeProfileRepository;

        log.info("意图路由 Agent 构建完成：3 路子 Agent（code-review-pipeline / combined-interview / interviewer），fallback=interviewer");
    }

    /**
     * 统一入口：所有面试对话都从这里进（SSE 流式）。
     *
     * 流程：快路径判断 → 组装通用上下文 → LLM 分诊 → 子 Agent 干活 → 过滤成文本流 → 收尾。
     *
     * 消息组装为什么只有一次机会（重要设计约束）：
     *   路由发生在图内部，我们没法"先路由、再按意图定制上下文"。
     *   所以这里组装的是一份【通用上下文】（题库知识 + 窗口历史 + 用户消息），
     *   哪个子 Agent 被选中都吃这份。本题库知识对面试官本来就适用，
     *   similarityThreshold 0.7 也会滤掉大部分闲聊噪声，代价可控。
     *
     * @param userMessage 用户消息
     * @param sessionId   会话 id（threadId，隔离记忆/清单/checkpoint）
     * @param candidateId 候选人 id（4.3 新增）：写入 config metadata，供工具跨会话聚合
     */
    public Flux<String> chat(String userMessage, String sessionId, String candidateId) {

        // ── 快路径：4.1 落库复用的保留 ──
        // 同会话已分析过代码 + 用户仍是代码评审意图 → 直接进面试官（清单 loadCodeFacts 前置注入）。
        // 为什么还需要规则判断 isCodeReviewRequest？因为"复用"只对代码评审有意义，
        // 而路由在图上跑、拦不住，所以复用判断留在外层最便宜。
        if (codeProfileRepository.existsBySessionId(sessionId)
                && CodeReviewPipeline.isCodeReviewRequest(userMessage)) {
            log.info("会话 {} 已有代码分析记录且用户仍请求代码评审，走复用快路径（跳过分析、跳过路由）", sessionId);
            return interviewer.chat(userMessage, sessionId, candidateId);
        }

        // ── 组装上下文（buildChatMessages，基类）→ 开跑分诊图 ──
        // 图内部先跑路由节点（LLM 选科室——路由决策是内部结构化输出，不会混进消息流），
        // 再跑被选中子 Agent 的节点，流式吐出消息。过滤成"助手说的话" + 收尾在基类完成。
        log.info("意图路由启动：sessionId={}，交给路由 LLM 分诊", sessionId);

        // threadId = sessionId：整张路由图（含被选中的子 Agent）共用同一把会话钥匙；
        // metadata 写入 candidateId（4.3）：工具经 SessionKeys.candidateId 从同一 config 读回（"写端"）
        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .addMetadata(SessionKeys.CANDIDATE_ID_KEY, candidateId)
                .build();

        Flux<Message> agentStream;
        try {
            agentStream = router.streamMessages(buildChatMessages(userMessage, sessionId, candidateId), config);
        } catch (GraphRunnerException e) {
            log.error("意图路由启动失败：sessionId={}", sessionId, e);
            return Flux.error(e);
        }
        return streamAssistantAnswers(agentStream, userMessage, config);
    }

}
