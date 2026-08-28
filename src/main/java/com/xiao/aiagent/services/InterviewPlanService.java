package com.xiao.aiagent.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.repository.CodeProfileRepository;
import com.xiao.aiagent.repository.InterviewPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 面试规划服务（阶段 5.2 新增）——"考什么"的范围契约的生产方。
 *
 * 定位：这是一个【带 LLM 的普通函数】，不是 Agent——输入固定（JD 画像/用户消息）、
 * 输出固定（考点计划 JSON），没有工具循环与临场决策，配不上 ReactAgent 的重机器
 * （判据见《阶段5-评估系统改造计划》§0.3）。落库由本服务代码直接执行，不经 LLM 工具。
 *
 * 职责：ensurePlan 为一场面试保证"存在一份计划"（没有则生成），三个消费方共用：
 *   ① 面试官：renderPlan 渲染成 system 消息注入，按计划考点/优先级/难度档出题；
 *   ② 评估官（5.3）：parseTopics 取考点词表，评分归类的 topic 必须从词表选（封闭词表）；
 *   ③ 报告（5.4）：计划 vs 实际覆盖矩阵的对照基准。
 *
 * 计划生命周期（一场面试一份，sessionId 定位）：
 *   首轮：ensurePlan 查库 → 无 → 生成落库。此时若 JD 画像已在库（重开场景）→ source=JD；
 *         否则（综合面试首轮，分析官还没跑）只能按用户消息出临时计划 source=USER——
 *         这保证第一题就有出题基准，不必等分析跑完。
 *   升级：后续轮次发现"计划还是 USER 版 + 该会话已落了 JD 画像" → 用 JD 画像重生成一次
 *         （source 改 JD）。此后考核基准即 JD 考察矩阵，报告的覆盖矩阵以它为准。
 *   不变：除 USER→JD 升级外不改计划；中途新考的领域按"计划外考点"进报告（诚实呈现）。
 *
 * 失败降级：LLM 输出解析失败重试 1 次，仍失败返回 null——计划是增强项不是阻塞项，
 *   面试照常进行、调用方跳过注入（与"题库检索不到就跳过"同一降级哲学）。
 */
@Service
public class InterviewPlanService {

    private static final Logger log = LoggerFactory.getLogger(InterviewPlanService.class);

    /** 计划来源：JD=依据 JD 考察矩阵（评分基准）；USER=仅依据用户消息（临时计划，待升级） */
    public static final String SOURCE_JD = "JD";
    public static final String SOURCE_USER = "USER";

    /** JD 画像落库时的 profileType（与 JdAnalyzerAgent 约定一致，升级探测用） */
    private static final String PROFILE_TYPE_JD = "JD";
    /** 生成失败重试次数：flash 模型 JSON 输出遵从度一般，一次重试换可观的成功率（风险见计划 §8.1） */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * 规划 prompt——与评估官/报告各管一段，互不越界：
     * 规划管"考什么"（plan），面试官管"怎么问"，评估官管"答得几分"，报告管"考得怎样"。
     */
    private static final String PLANNER_PROMPT = """
            你是面试规划官。根据给定的 JD 考察画像（可能有）和用户消息，为一场技术面试制定考点计划。
            要求：
            1. 有【JD 考察画像】时以它为主依据：画像中 interviewFocus/interview 的每一条考察点都必须落成一个考点，不得遗漏；
            2. 没有 JD 画像时：从用户消息中识别面试领域（如"来一场 Java 并发面试"→Java 并发），围绕该领域规划 3~6 个由浅入深的考点；
            3. 每个考点给出 priority（高/中/低，考察优先级）和 difficulty（基础/进阶/挑战，难度档）；
            4. 只输出 JSON 数组，不要输出任何解释文字或围栏。
            输出格式示例：
            [{"topic":"线程池参数","priority":"高","difficulty":"进阶"},{"topic":"synchronized 与 Lock 的区别","priority":"中","difficulty":"基础"}]
            """;

    /** 考点计划条目（词表的最小单元）。priority/difficulty 取值见 normalize* 的合法值域 */
    public record PlanTopic(String topic, String priority, String difficulty) {
    }

    private final ChatModel chatModel;
    private final CodeProfileRepository codeProfileRepository;
    private final InterviewPlanRepository interviewPlanRepository;
    private final ObjectMapper objectMapper;

    public InterviewPlanService(ChatModel chatModel,
                                CodeProfileRepository codeProfileRepository,
                                InterviewPlanRepository interviewPlanRepository,
                                ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.codeProfileRepository = codeProfileRepository;
        this.interviewPlanRepository = interviewPlanRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 保证该会话存在面试计划，返回计划；生成失败返回 null（调用方跳过注入，不阻塞面试）。
     * 幂等：已有计划直接返回（每轮调用只花一次索引查询）；唯一会重生成的情况是 USER→JD 升级。
     *
     * @param userMessage 用户本轮消息（首轮生成计划的领域依据；升级时作辅助输入）
     * @param sessionId   会话 id（一场一份计划）
     * @param candidateId 候选人 id（落库留档，报告侧跨会话对照预留）
     */
    public InterviewPlan ensurePlan(String userMessage, String sessionId, String candidateId) {
        Optional<InterviewPlan> existing = interviewPlanRepository.findBySessionId(sessionId);
        if (existing.isPresent()) {
            InterviewPlan plan = existing.get();
            // 升级规则：临时计划（USER）+ JD 画像已落库 → 用 JD 考察矩阵重生成一次（评分基准从此以 JD 为准）
            if (SOURCE_USER.equals(plan.getSource())
                    && codeProfileRepository.existsBySessionIdAndProfileType(sessionId, PROFILE_TYPE_JD)) {
                log.info("检测到 JD 画像已落库，升级面试计划：sessionId={}（USER → JD）", sessionId);
                InterviewPlan upgraded = generate(userMessage, sessionId, candidateId);
                if (upgraded != null) {
                    plan.setTopicsJson(upgraded.getTopicsJson());
                    plan.setSource(SOURCE_JD);
                    plan.setCreatedAt(LocalDateTime.now());
                    InterviewPlan saved = interviewPlanRepository.save(plan);
                    log.info("面试计划升级完成：sessionId={}，新考点={}", sessionId,
                            parseTopics(saved.getTopicsJson()).stream().map(PlanTopic::topic).toList());
                    return saved;
                }
                log.warn("面试计划升级失败，沿用原临时计划：sessionId={}", sessionId);
            }
            return plan;
        }

        InterviewPlan created = generate(userMessage, sessionId, candidateId);
        if (created == null) {
            return null;
        }
        InterviewPlan saved = interviewPlanRepository.save(created);
        log.info("面试计划落库：id={}, sessionId={}, source={}, 考点={}",
                saved.getId(), sessionId, saved.getSource(),
                parseTopics(saved.getTopicsJson()).stream().map(PlanTopic::topic).toList());
        return saved;
    }

    /** 读取某场面试的计划（评估官/报告用；无计划返回 empty，消费方自行降级） */
    public Optional<InterviewPlan> loadPlan(String sessionId) {
        return interviewPlanRepository.findBySessionId(sessionId);
    }

    /**
     * 渲染计划为 system 注入文本（面试官上下文用）；无计划/无考点返回空串，调用方跳过注入。
     * 每轮注入而非仅首轮：文本只有几行，成本可忽略，换来"模型每轮都被拴在计划上"。
     */
    public String renderPlan(InterviewPlan plan) {
        if (plan == null) {
            return "";
        }
        List<PlanTopic> topics = parseTopics(plan.getTopicsJson());
        if (topics.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【面试计划】本场面试按以下考点出题，优先覆盖尚未考察过的高优先级考点，题目难度对齐考点难度档：\n");
        int i = 1;
        for (PlanTopic t : topics) {
            sb.append(i++).append(". ").append(t.topic())
              .append("（优先级：").append(t.priority()).append("，难度：").append(t.difficulty()).append("）\n");
        }
        return sb.toString();
    }

    /**
     * 解析考点词表（评估官的 topic 封闭词表来源）。
     * 解析内做清洗：topic 去空白/去重，priority/difficulty 归一到合法值域——
     * 词表是后续所有精确匹配的基准，脏值必须挡在源头。
     */
    public List<PlanTopic> parseTopics(String topicsJson) {
        if (topicsJson == null || topicsJson.isBlank()) {
            return List.of();
        }
        try {
            PlanTopic[] arr = objectMapper.readValue(LlmJson.extract(topicsJson), PlanTopic[].class);
            List<PlanTopic> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (PlanTopic t : arr) {
                if (t == null || t.topic() == null || t.topic().isBlank()) {
                    continue;
                }
                String topic = t.topic().trim();
                if (!seen.add(topic)) {
                    continue;
                }
                result.add(new PlanTopic(topic, normalizePriority(t.priority()), normalizeDifficulty(t.difficulty())));
            }
            return result;
        } catch (Exception e) {
            log.warn("考点计划 JSON 解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 生成一次计划（不落库）；失败返回 null。生成依据：JD 画像优先，用户消息兜底/辅助 */
    private InterviewPlan generate(String userMessage, String sessionId, String candidateId) {
        String input = buildPlannerInput(userMessage, sessionId);
        boolean hasJd = jdProfileExists(sessionId);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String raw = callPlanner(input);
                List<PlanTopic> topics = parseTopics(raw);
                if (topics.isEmpty()) {
                    throw new IllegalStateException("计划考点为空（输出无法解析为考点数组）");
                }
                String json = objectMapper.writeValueAsString(topics);
                return new InterviewPlan(sessionId, candidateId, json,
                        hasJd ? SOURCE_JD : SOURCE_USER, LocalDateTime.now());
            } catch (Exception e) {
                log.warn("面试计划生成失败（第 {}/{} 次）：sessionId={}, {}",
                        attempt, MAX_ATTEMPTS, sessionId, e.getMessage());
            }
        }
        return null;
    }

    /** 组装规划输入：有 JD 画像以它为主依据（interviewFocus 一条不落），用户消息始终附带作辅助 */
    private String buildPlannerInput(String userMessage, String sessionId) {
        StringBuilder sb = new StringBuilder();
        String jdFacts = findJdFacts(sessionId);
        if (jdFacts != null) {
            sb.append("【JD 考察画像】\n").append(jdFacts).append("\n\n");
        }
        sb.append("【用户消息】\n").append(userMessage);
        return sb.toString();
    }

    private String callPlanner(String input) throws JsonProcessingException {
        List<Message> messages = List.of(new SystemMessage(PLANNER_PROMPT), new UserMessage(input));
        ChatResponse response = chatModel.call(new Prompt(messages));
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : LlmJson.extract(text);
    }

    private boolean jdProfileExists(String sessionId) {
        return codeProfileRepository.existsBySessionIdAndProfileType(sessionId, PROFILE_TYPE_JD);
    }

    /** 取该会话 JD 画像的 factsJson（多条取第一条）；没有返回 null */
    private String findJdFacts(String sessionId) {
        return codeProfileRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .filter(p -> PROFILE_TYPE_JD.equals(p.getProfileType()))
                .findFirst()
                .map(p -> p.getFactsJson())
                .orElse(null);
    }

    private static String normalizePriority(String p) {
        if (p == null) return "中";
        if (p.contains("高")) return "高";
        if (p.contains("低")) return "低";
        return "中";
    }

    private static String normalizeDifficulty(String d) {
        if (d == null) return "进阶";
        if (d.contains("基础") || d.contains("简单")) return "基础";
        if (d.contains("挑战") || d.contains("难")) return "挑战";
        return "进阶";
    }

}
