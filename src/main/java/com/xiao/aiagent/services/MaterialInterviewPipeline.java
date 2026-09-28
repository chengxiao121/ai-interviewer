package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent.ConcatenationMergeStrategy;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.xiao.aiagent.repository.CodeProfileRepository;
import com.xiao.aiagent.tools.SessionKeys;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 资料分析面试流水线（阶段 5.5 新增）——代码评审流水线（4.1）与综合面试流水线（4.2）的收敛体。
 *
 * 为什么收敛：两个旧流水线是同一个模式（"分析官产物料 → 面试官消费"）写了两遍，
 * 唯一区别是分析官个数（1 个 vs 3 个）；每新增一种资料组合就要新写一个类。
 * 收敛后：一个类，分析官集合是参数——用户给了什么资料就挂什么分析官，
 * "代码评审 / JD+简历 / 三类综合"不再是三种编排，是同一个流水线的三种入参。
 *
 * ── 编排结构（与旧流水线一致的两层）────────────────────────
 *   ParallelAgent(选中的分析官 × N)  →  面试官
 *   内层并行：各资料分析彼此独立，并行省时；
 *   外层串行：面试必须等分析结束拿到物料才能出题。
 *   编排管时序、数据走数据库：接力棒（事实清单）通过 code_profile 表传递（4.1 契约延续）。
 * ─────────────────────────────────────────────────────────
 *
 * 按组合缓存构建：分析官组合最多 2³=8 种，按 bitmask 缓存 SequentialAgent
 * （延续"构造时 build 一次、之后复用"的既有模式，只是从单实例变按组合缓存）。
 * 路由注册的 getPipeline() 是全量组合（三个分析官都在），配合分析官 prompt 里的
 * 【资料守门】——没有自己负责资料的分析官直接跳过不落库（见 ProfileAnalyzerAgent）。
 *
 * 复用路径按【资料类型】逐类判断（泛化自 4.1 的 existsBySessionId 整体判断）：
 *   用户本轮提到的资料里，库里已有对应 profileType 清单的类型跳过分析（复用红利按类生效）；
 *   全部都已分析过（或本次没提资料、被语义路由带进来）→ 直连面试官，一次分析官 LLM 调用都不花。
 */
@Service
public class MaterialInterviewPipeline extends StreamingPipelineSupport {

    /** 资料类型：规则检测的产出，也是 code_profile.profileType 的取值（与三个分析官落库约定一致） */
    public enum Material {
        CODE("CODE"), JD("JD"), RESUME("RESUME");

        private final String profileType;

        Material(String profileType) {
            this.profileType = profileType;
        }

        public String profileType() {
            return profileType;
        }
    }

    // ── 资料检测规则（自 CodeReviewPipeline.isCodeReviewRequest 迁移 + JD/简历推广）──
    // 为什么用规则做资料检测而不是全交给 LLM 路由：路径/扩展名/关键词是"能确定答案的便宜事"，
    // 规则命中直接进流水线（省一次路由 LLM 调用、且组合判断确定性 100%）；
    // 规则漏掉的语义表述（"看看上周写的那个线程池"）由上层 LlmRoutingAgent 兜住。
    /** 匹配文件扩展名（判断消息里是否提到代码文件） */
    private static final Pattern FILE_EXT = Pattern.compile(
            "\\.(java|py|go|js|ts|jsx|tsx|c|cpp|h|kt|rb|php|sql|html|css|yml|yaml|xml|sh|md)\\b",
            Pattern.CASE_INSENSITIVE);
    /** 匹配 Windows 盘符路径（如 D:\... 或 D:/...） */
    private static final Pattern WIN_PATH = Pattern.compile("[A-Za-z]:[\\\\/]");
    /** 代码评审意图关键词 */
    private static final List<String> CODE_KEYWORDS = List.of(
            "代码评审", "评审我的", "看我的代码", "看下我的代码", "我的实现", "针对我的", "看我代码", "这段代码");
    /**
     * JD 意图匹配（验收修复）：只用子串包含会把 JDK/JDBC 误判成"提到了 JD 资料"——
     * 实测候选人回答里的"JDK 提供的工具类"被送进资料流水线。用 `JD(?!K|BC)` 排除 JDK/JDBC，
     * 保留"这个 JD""JD 里要求"这类真实表述。
     */
    private static final Pattern JD_PATTERN = Pattern.compile("JD(?!(K|BC))", Pattern.CASE_INSENSITIVE);
    /** JD 中文/英文关键词（不含"JD"本身——那走 JD_PATTERN） */
    private static final List<String> JD_KEYWORDS = List.of("岗位", "职位", "job description");
    /** 简历意图关键词；CV 用词边界匹配，避免嵌在英文单词里误报 */
    private static final List<String> RESUME_KEYWORDS = List.of("简历", "resume");
    private static final Pattern CV_PATTERN = Pattern.compile("(?<![A-Za-z])CV(?![A-Za-z])", Pattern.CASE_INSENSITIVE);

    /** 组合缓存：bitmask → 编排图（8 种组合封顶，实际常用的只有少数几种） */
    private final Map<Integer, SequentialAgent> pipelineCache = new ConcurrentHashMap<>();
    // 三个分析官 + 面试官 + 复用判断的仓储
    private final CodeAnalyzerAgent codeAnalyzer;
    private final JdAnalyzerAgent jdAnalyzer;
    private final ResumeAnalyzerAgent resumeAnalyzer;
    private final InterviewAssistant interviewer;
    private final CodeProfileRepository codeProfileRepository;

    public MaterialInterviewPipeline(CodeAnalyzerAgent codeAnalyzer,
                                     JdAnalyzerAgent jdAnalyzer,
                                     ResumeAnalyzerAgent resumeAnalyzer,
                                     InterviewAssistant interviewer,
                                     CodeProfileRepository codeProfileRepository,
                                     ChatMemory chatMemory,
                                     MemorySaver memorySaver,
                                     KnowledgeSearchService knowledgeSearchService,
                                     WeaknessProfileService weaknessProfileService,
                                     InterviewPlanService interviewPlanService,
                                     AnswerEvaluatorService answerEvaluatorService) {

        super(chatMemory, memorySaver, knowledgeSearchService, weaknessProfileService,
                interviewPlanService, answerEvaluatorService);

        this.codeAnalyzer = codeAnalyzer;
        this.jdAnalyzer = jdAnalyzer;
        this.resumeAnalyzer = resumeAnalyzer;
        this.interviewer = interviewer;
        this.codeProfileRepository = codeProfileRepository;

        log.info("资料分析流水线就绪：分析官按组合惰性构建（最多 8 种），全量组合供路由注册");
    }

    /**
     * 规则检测消息里提到的资料类型（静态：AgentRouter 快路径与语义路由前置判断共用）。
     * 三类独立检测、可叠加（"拿这份 JD 和我的代码面我"→ JD+CODE 两种都命中）。
     */
    public static Set<Material> detectMaterials(String userMessage) {
        EnumSet<Material> materials = EnumSet.noneOf(Material.class);
        if (userMessage == null || userMessage.isBlank()) {
            return materials;
        }
        if (WIN_PATH.matcher(userMessage).find()
                || FILE_EXT.matcher(userMessage).find()
                || containsAny(userMessage, CODE_KEYWORDS)) {
            materials.add(Material.CODE);
        }
        if (JD_PATTERN.matcher(userMessage).find() || containsAny(userMessage, JD_KEYWORDS)) {
            materials.add(Material.JD);
        }
        if (CV_PATTERN.matcher(userMessage).find() || containsAny(userMessage, RESUME_KEYWORDS)) {
            materials.add(Material.RESUME);
        }
        return materials;
    }

    /**
     * 暴露全量组合流水线给上层编排器（AgentRouter）注册为路由子 Agent。
     * 全量组合 = 三个分析官都在：路由 LLM 只需要决定"是否资料面试"，
     * 不需要判断具体组合——组合由分析官的【资料守门】在运行时自筛（没有资料的分析官直接跳过）。
     */
    public SequentialAgent getPipeline() {
        return pipelineFor(EnumSet.allOf(Material.class));
    }

    /**
     * 发起一次资料分析面试（SSE 流式）。
     *
     * 路径 A（复用路径）：本轮提到的资料全部已在库 → 直连面试官（loadCodeFacts/getCodeFacts 取清单），
     *   省掉全部分析官调用——"落库而非内存传递"的红利按资料类型逐类兑现；
     * 路径 B（流水线路径）：有未分析的资料 → Parallel(待分析的分析官) → 面试官。
     *
     * @param userMessage 用户消息（含资料路径/粘贴文本/资料意图关键词）
     * @param sessionId   会话 id（threadId，隔离清单/记忆/checkpoint）
     * @param candidateId 候选人 id（写入 config metadata，供工具跨会话聚合）
     */
    public Flux<String> chat(String userMessage, String sessionId, String candidateId) {

        Set<Material> mentioned = detectMaterials(userMessage);
        Set<Material> pending = EnumSet.noneOf(Material.class);
        for (Material m : mentioned) {
            if (!codeProfileRepository.existsBySessionIdAndProfileType(sessionId, m.profileType())) {
                pending.add(m);
            }
        }

        // ── 路径 A：复用路径（无新增资料可分析）──
        if (pending.isEmpty()) {
            log.info("资料均已分析过（或未提资料），跳过分析官直连面试官：sessionId={}, mentioned={}",
                    sessionId, mentioned);
            return interviewer.chat(userMessage, sessionId, candidateId);
        }

        // ── 路径 B：流水线路径（只跑缺的那几类分析官）──
        log.info("资料分析流水线启动：sessionId={}, 待分析={}, 其余类型复用库中清单", sessionId, pending);
        SequentialAgent pipeline = pipelineFor(pending);

        // threadId = sessionId：子 Agent 工具经 SessionKeys 归一化取根会话；
        // metadata 写入 candidateId：工具经 SessionKeys.candidateId 读回（"写端"）
        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .addMetadata(SessionKeys.CANDIDATE_ID_KEY, candidateId)
                .build();

        Flux<Message> agentStream;
        try {
            // 注意：分析官的输出文字会混进消息流，属预期行为（后端架构文档风险 15）；
            // 过滤成"助手说的话" + 收尾在基类完成。
            agentStream = pipeline.streamMessages(buildChatMessages(userMessage, sessionId, candidateId), config);
        } catch (GraphRunnerException e) {
            log.error("资料分析流水线启动失败：sessionId={}", sessionId, e);
            return Flux.error(e);
        }
        return streamAssistantAnswers(agentStream, userMessage, config);
    }

    /**
     * 带上传资料的面试入口（阶段 7 新增）——「附件确定性注入」路径。
     *
     * 与 chat() 的区别：资料类型与文本由上传层（MaterialStoreService）给死，
     * 不做消息正则识别——"用户点按钮选文件"本身就是最可靠的意图信号；
     * 复用判断与 chat() 同一套（按类型逐类查库，已分析过的类型跳过）。
     *
     * 记忆策略（上传相对粘贴的核心收益）：资料全文拼进【给模型看】的消息
     * （分析官的资料守门从消息里找资料，粘贴路径同款），但写回会话记忆的
     * 只有用户原话——资料全文不进长期记忆窗口，不挤占后续问答的上下文。
     *
     * @param userMessage   用户本轮原话（可为空——只传资料不打字时用默认开场句）
     * @param materialTexts 上传资料文本（按类型归组，来自 MaterialStoreService.loadAsTexts）
     * @param sessionId     会话 id（threadId，隔离清单/记忆/checkpoint）
     * @param candidateId   候选人 id（写入 config metadata，供工具跨会话聚合）
     */
    public Flux<String> chatWithMaterials(String userMessage,
                                          Map<Material, String> materialTexts,
                                          String sessionId,
                                          String candidateId) {
        if (materialTexts == null || materialTexts.isEmpty()) {
            throw new IllegalArgumentException("materialTexts 为空");
        }
        String effectiveMessage = userMessage == null || userMessage.isBlank()
                ? "请根据我上传的资料开始面试我。"
                : userMessage.trim();

        // 待分析 = 上传类型中尚未落库的（复用红利按类型逐类生效，与 chat() 同判据）
        Set<Material> pending = EnumSet.noneOf(Material.class);
        for (Material m : materialTexts.keySet()) {
            if (!codeProfileRepository.existsBySessionIdAndProfileType(sessionId, m.profileType())) {
                pending.add(m);
            }
        }

        // 复用路径：上传资料均已分析过 → 直连面试官（它自己会 loadCodeFacts 注入库中清单）
        if (pending.isEmpty()) {
            log.info("上传资料均已分析过，直连面试官：sessionId={}, types={}", sessionId, materialTexts.keySet());
            return interviewer.chat(effectiveMessage, sessionId, candidateId);
        }

        // 流水线路径：只跑缺的那几类分析官
        log.info("上传资料流水线启动：sessionId={}, 待分析={}", sessionId, pending);
        SequentialAgent pipeline = pipelineFor(pending);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .addMetadata(SessionKeys.CANDIDATE_ID_KEY, candidateId)
                .build();

        // 给模型看的消息 = 用户原话 + 上传资料块（分析官守门从消息里找资料，与粘贴路径同款）
        String analyzerMessage = buildMaterialMessage(effectiveMessage, materialTexts);

        Flux<Message> agentStream;
        try {
            agentStream = pipeline.streamMessages(buildChatMessages(analyzerMessage, sessionId, candidateId), config);
        } catch (GraphRunnerException e) {
            log.error("上传资料流水线启动失败：sessionId={}", sessionId, e);
            return Flux.error(e);
        }
        // 收尾写回记忆用【用户原话】——资料全文只在本轮图内可见，不进会话记忆
        return streamAssistantAnswers(agentStream, effectiveMessage, config);
    }

    /** 拼接"用户原话 + 上传资料块"（分析官与面试官本轮共享的完整输入） */
    private static String buildMaterialMessage(String userMessage, Map<Material, String> materialTexts) {
        StringBuilder sb = new StringBuilder(userMessage);
        for (Map.Entry<Material, String> entry : materialTexts.entrySet()) {
            sb.append("\n\n【上传资料·").append(entry.getKey().profileType()).append("】\n")
                    .append(entry.getValue());
        }
        return sb.toString();
    }

    /** 按组合取/建编排图（bitmask 缓存，computeIfAbsent 保证同组合只 build 一次） */
    private SequentialAgent pipelineFor(Set<Material> materials) {
        return pipelineCache.computeIfAbsent(bitmask(materials), k -> build(materials));
    }

    /** 组装"分析 → 面试官"两层编排图（分析官顺序：JD → 简历 → 代码，与 4.2 综合流水线一致） */
    private SequentialAgent build(Set<Material> materials) {
        // ParallelAgent.subAgents 要求 Agent 接口列表（ReactAgent 是其实现）
        List<Agent> analyzers = new ArrayList<>();
        if (materials.contains(Material.JD)) {
            analyzers.add(jdAnalyzer.getAgent());
        }
        if (materials.contains(Material.RESUME)) {
            analyzers.add(resumeAnalyzer.getAgent());
        }
        if (materials.contains(Material.CODE)) {
            analyzers.add(codeAnalyzer.getAgent());
        }

        // 分析阶段（验收修复）：多个分析官 → ParallelAgent 并行（4.2 综合形态）；
        // 单个分析官 → 直接挂进 SequentialAgent（4.1 双 Agent 形态）。
        // 为什么不能统一用 ParallelAgent：框架校验要求至少 2 个子 Agent——
        // 实测报 "ParallelAgent requires at least 2 sub-agents"，单资料面试（只给代码/JD/简历）会 500。
        Agent analysisStage;
        if (analyzers.size() == 1) {
            analysisStage = analyzers.get(0);
        } else {
            analysisStage = ParallelAgent.builder()
                    .name("parallel-analyzers")
                    .description("资料分析官并行分析（" + analyzers.size() + " 路），合并事实清单")
                    .subAgents(analyzers)
                    .mergeStrategy(new ConcatenationMergeStrategy("\n\n"))
                    .mergeOutputKey("merged_facts")
                    .maxConcurrency(analyzers.size())
                    .build();
        }

        // 外层：分析完 → 面试官出题（面试官必须在分析之后——时序由编排保证）
        SequentialAgent pipeline = SequentialAgent.builder()
                .name("material-interview")
                .description("资料分析面试流水线：" + analyzers.size() + " 路分析官 → 面试官出题")
                .subAgents(List.of(analysisStage, interviewer.getAgent()))
                .saver(memorySaver)
                .build();

        log.info("构建资料分析流水线：组合={}（缓存键 {}）", materials, bitmask(materials));
        return pipeline;
    }

    private static int bitmask(Set<Material> materials) {
        int key = 0;
        if (materials.contains(Material.JD)) key |= 2;
        if (materials.contains(Material.RESUME)) key |= 4;
        if (materials.contains(Material.CODE)) key |= 1;
        return key;
    }

    private static boolean containsAny(String message, List<String> keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

}
