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
import java.util.Map;
import java.util.Set;

/**
 * 意图路由 Agent（阶段 4.2 新增，5.5 收敛改版）——多 Agent 系统的【统一入口 / 分诊台】。
 *
 * ── 如何理解这个文件（心智模型）────────────────────────────
 * 把本类想成医院的"分诊台护士"：患者（用户消息）来了，按三道关口分诊：
 *   第一道（复用快路径）：该会话分析过资料、且本条消息没提新资料 → 一定是继续面试，
 *                         直连面试官（省路由 LLM、省分析）；
 *   第二道（规则路径）：  消息明确提到资料（路径/扩展名/关键词）→ 直接进资料分析流水线
 *                         （MaterialInterviewPipeline，组合判断确定性 100%，省路由 LLM）；
 *   第三道（语义路由）：  规则没认出资料、但用户可能用自然语言提了资料 → LLM 分诊
 *                         （LlmRoutingAgent 两选一，拿不准 → 面试官，fallback 双保险）。
 * 分诊台自己不看病：它只做"分派"，真正的活是子 Agent 干的。
 * ─────────────────────────────────────────────────────────
 *
 * 5.5 为什么从"三路子 Agent"改成"规则前置 + 两路语义路由"：
 *   旧结构里 code-review / combined 两条流水线是同一模式的两份拷贝，收敛成
 *   MaterialInterviewPipeline 后，"具体挂哪几个分析官"由资料检测（规则）决定，
 *   路由 LLM 只需要回答一个问题——"这是不是带资料的分析型面试"。判断维度越少，
 *   概率路由越准（裁剪闲聊 Agent 的同一逻辑：能不用概率决策的地方就不用）。
 *
 * 为什么不设独立的「闲聊 Agent」：闲聊频率低、无业务价值，面试官 prompt 吸收
 * （寒暄简短回应后引导回面试主题）；单独开路只会增加误路由面（4.2 裁剪决策，延续）。
 *
 * LlmRoutingAgent 内部机制（读框架源码总结）：路由节点把子 Agent 的 name+description
 * 列给路由 LLM → LLM 返回 Agent 名列表 → 校验失败带错误重试 → 仍失败走 fallbackAgent。
 * 所以子 Agent 的 name/description 写得好不好，直接决定路由准不准。
 */
@Service
public class AgentRouter extends StreamingPipelineSupport {

    /**
     * 路由指令——给"分诊台 LLM"看的分类标准（只在规则没认出资料时才会被用到）。
     * 指令里写了"拿不准时选 interviewer"，与 fallbackAgent 形成双保险。
     */
    private static final String ROUTING_INSTRUCTION = """
            你是面试系统的意图路由器。根据用户消息的内容，从下方可用 Agent 中选择最合适的一个：
            1. 用户以自然语言提到自己的资料并要求据此面试/评审（JD/岗位描述、简历、代码——
               可能没有给出具体路径，例如"帮我看看上周写的那个线程池"、"拿我的简历来面我"）→ 选 material-interview；
            2. 其余情况（出题、追问、点评、答题统计、继续面试，以及寒暄、闲聊等）→ 选 interviewer；
            3. 拿不准时 → 选 interviewer（面试是主场景）。
            """;

    // 路由图：构造时 build 一次，之后每次请求复用（与流水线同模式）
    private final LlmRoutingAgent router;
    // 面试官（复用快路径 / 兜底路径直接调它的 chat()）
    private final InterviewAssistant interviewer;
    // 资料分析流水线（规则路径直接调它的 chat()；getPipeline() 注册为路由子 Agent）
    private final MaterialInterviewPipeline materialInterviewPipeline;
    // 判断"该会话分析过资料没"——复用快路径的开关
    private final CodeProfileRepository codeProfileRepository;
    // 上传资料存储（阶段 7）：chatWithMaterials 按 materialIds 取文本
    private final MaterialStoreService materialStoreService;

    public AgentRouter(ChatModel chatModel,
                       MaterialInterviewPipeline materialInterviewPipeline,
                       InterviewAssistant interviewer,
                       CodeProfileRepository codeProfileRepository,
                       MaterialStoreService materialStoreService,
                       ChatMemory chatMemory,
                       MemorySaver memorySaver,
                       KnowledgeSearchService knowledgeSearchService,
                       WeaknessProfileService weaknessProfileService,
                       InterviewPlanService interviewPlanService,
                       AnswerEvaluatorService answerEvaluatorService) {

        super(chatMemory, memorySaver, knowledgeSearchService, weaknessProfileService,
                interviewPlanService, answerEvaluatorService);

        // ── 组装"分诊台"（只剩两个科室）──
        // material-interview 注册的是全量组合（三个分析官都在）：路由 LLM 只判断"是不是资料面试"，
        // 具体挂哪几个分析官由分析官的【资料守门】在运行时自筛（没有资料的分析官直接跳过不落库）。
        this.router = LlmRoutingAgent.builder()
                .name("intent-router")                                          // 分诊台自己的名字（日志可见）
                .description("意图路由器：根据用户消息意图，分派给最合适的子 Agent")
                .model(chatModel)                                              // 路由决策靠 LLM 做，必须配模型
                .instruction(ROUTING_INSTRUCTION)                              // 告诉路由 LLM 分类标准
                .subAgents(List.of(
                        materialInterviewPipeline.getPipeline(),  // ① 带资料的分析型面试 → 收敛后的统一流水线
                        interviewer.getAgent()))                  // ② 其余全部（出题/追问/评分/闲聊）→ 面试官
                .fallbackAgent("interviewer")                   // 兜底：路由失败时去面试官（主场景，风险 16）
                .saver(memorySaver)                             // checkpoint 按 threadId 隔离，跑完 release
                .build();

        this.materialInterviewPipeline = materialInterviewPipeline;
        this.interviewer = interviewer;
        this.codeProfileRepository = codeProfileRepository;
        this.materialStoreService = materialStoreService;

        log.info("意图路由 Agent 构建完成：规则前置（复用快路径 + 资料规则路径）+ 2 路语义路由（material-interview / interviewer），fallback=interviewer");
    }

    /**
     * 统一入口：所有面试对话都从这里进（SSE 流式）。
     *
     * 三道关口见类注释；能过前两道的不花路由 LLM 调用——"能确定答案的便宜事，不必每次都问 LLM"。
     * 只有走到第三道才组装通用上下文开跑路由图（路由发生在图内部，无法先路由再定制上下文）。
     *
     * @param userMessage 用户消息
     * @param sessionId   会话 id（threadId，隔离记忆/清单/checkpoint）
     * @param candidateId 候选人 id：写入 config metadata，供工具跨会话聚合
     */
    public Flux<String> chat(String userMessage, String sessionId, String candidateId) {

        Set<MaterialInterviewPipeline.Material> materials = MaterialInterviewPipeline.detectMaterials(userMessage);

        // ── 第一道：复用快路径（4.1 引入，5.5 泛化）──
        // 该会话已分析过资料 + 本条消息没提新资料 → 必然是继续面试 → 直连面试官。
        // （面试官 loadCodeFacts 会前置注入库中全部清单；统计/追问/寒暄也都由它吸收）
        if (codeProfileRepository.existsBySessionId(sessionId) && materials.isEmpty()) {
            log.info("会话 {} 已有资料分析记录且未提新资料，走复用快路径直连面试官（跳过路由）", sessionId);
            return interviewer.chat(userMessage, sessionId, candidateId);
        }

        // ── 第二道：规则路径 ── 消息明确提到资料 → 直接进资料分析流水线（组合由流水线内部处理）
        if (!materials.isEmpty()) {
            log.info("规则识别到资料 {}，直接进资料分析流水线（跳过路由 LLM）：sessionId={}", materials, sessionId);
            return materialInterviewPipeline.chat(userMessage, sessionId, candidateId);
        }

        // ── 第三道：语义路由 ── 规则没认出资料，交给路由 LLM 判断"是不是用自然语言提了资料"
        log.info("意图路由启动：sessionId={}，交给路由 LLM 分诊", sessionId);

        // threadId = sessionId：整张路由图（含被选中的子 Agent）共用同一把会话钥匙；
        // metadata 写入 candidateId：工具经 SessionKeys.candidateId 从同一 config 读回（"写端"）
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

    /**
     * 带上传资料的入口（阶段 7 新增）——第四条路，但不需要"关口"：
     * 用户点按钮选了文件，类型与文本在上传时就已锁定，三道关口的概率判断全部跳过，
     * 直接进资料流水线（MaterialInterviewPipeline.chatWithMaterials）。
     *
     * 与 chat() 的分工：chat() 管"消息里可能藏资料"的分诊；本方法管"资料已确定"的直达。
     * Controller 按 materialIds 是否为空二选一，两者互斥覆盖全部入口。
     *
     * @param userMessage 用户本轮原话（可为空——只传资料不打字时流水线内部用默认开场句）
     * @param materialIds 上传资料 id 列表（来自 /api/materials/upload 的返回）
     * @param sessionId   会话 id
     * @param candidateId 候选人 id
     */
    public Flux<String> chatWithMaterials(String userMessage,
                                          List<String> materialIds,
                                          String sessionId,
                                          String candidateId) {
        Map<MaterialInterviewPipeline.Material, String> texts;
        try {
            texts = materialStoreService.loadAsTexts(materialIds);
        } catch (Exception e) {
            log.error("读取上传资料失败：sessionId={}, materialIds={}", sessionId, materialIds, e);
            return Flux.error(e);
        }
        log.info("上传资料路径启动：sessionId={}, materialIds={}, types={}", sessionId, materialIds, texts.keySet());
        return materialInterviewPipeline.chatWithMaterials(userMessage, texts, sessionId, candidateId);
    }

}
