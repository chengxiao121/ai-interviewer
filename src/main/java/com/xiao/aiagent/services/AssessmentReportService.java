package com.xiao.aiagent.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.entity.ScoreRecord;
import com.xiao.aiagent.repository.InterviewPlanRepository;
import com.xiao.aiagent.repository.ScoreRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估报告服务（阶段 5.4 新增）——"评估系统"交付物的生产方。
 *
 * 定位：与 InterviewPlanService / AnswerEvaluatorService 同类的【带 LLM 的普通函数】。
 * 挂在独立 REST 端点后面（AssessmentController），不进聊天路由——报告是交付物不是对话流，
 * 接口层面就区分了"对话流（SSE）"与"判定结果（结构化 JSON）"。
 *
 * 核心分工（计划 §1 原则 4：数字由代码算，叙事由 LLM 写）：
 *   Java 算数字：覆盖矩阵（计划 vs 实际逐考点对照，含证据链）、未覆盖考点、计划外考点——
 *               全部由 score_record 记录在内存分组聚合，数字可对账、可复现；
 *   LLM 写叙事：只做一次调用，输入=上述计算结果 JSON，输出总结/匹配度结论/建议，
 *               prompt 明令"只允许解读给定数字，禁止自造数字"（反幻觉护栏）。
 *
 * 覆盖矩阵的对照规则（与评估落库侧的封闭词表契约配套）：
 *   计划考点精确匹配到记录 → planned=true 的覆盖项；
 *   计划考点零记录 → uncoveredTopics（考了没/漏考）；
 *   记录 topic 以"计划外"开头或匹配不上任何计划考点 → outOfPlanTopics（如实呈现，不改写）。
 *   无计划的会话可出降级版报告（仅实际统计，注明无计划基准）。
 */
@Service
public class AssessmentReportService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentReportService.class);

    /** 证据链的展示截断（报告 JSON 体积控制；完整原文在库，报告只带节选） */
    private static final int QUESTION_EXCERPT_LEN = 200;
    private static final int ANSWER_EXCERPT_LEN = 300;
    private static final int FEEDBACK_EXCERPT_LEN = 300;
    /** 结论生成失败重试次数（与规划/评估同策略） */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * 叙事 prompt——反幻觉护栏：数字全部由代码算好传入，LLM 只许解读、不许自造。
     * 评分标准不在这里（评分在评估官），这里只做"统计 → 人话"的转译。
     */
    private static final String CONCLUSION_PROMPT = """
            你是面试评估报告的撰写人。下面给出一场面试的【统计数据 JSON】（覆盖矩阵/得分/未覆盖考点等，
            全部为程序计算的既成事实）。请撰写三个结论字段：
            1. overallSummary：本场表现总体总结（2~3 句，引用给定数字）；
            2. matchConclusion：对照考察计划的匹配度结论（覆盖了什么、漏了什么、强弱项是什么）；
            3. recommendations：后续复习/提升建议数组（3~5 条，针对薄弱考点与未覆盖考点）。
            硬性要求：只能使用给定 JSON 中出现的数字与考点名，禁止编造或修改任何数字；全程中文。
            只输出 JSON 对象，不要任何解释文字或围栏：
            {"overallSummary":"…","matchConclusion":"…","recommendations":["…","…"]}
            """;

    private final ChatModel chatModel;
    private final InterviewPlanRepository interviewPlanRepository;
    private final ScoreRecordRepository scoreRecordRepository;
    private final WeaknessProfileService weaknessProfileService;
    private final InterviewPlanService interviewPlanService;
    private final ObjectMapper objectMapper;

    public AssessmentReportService(ChatModel chatModel,
                                   InterviewPlanRepository interviewPlanRepository,
                                   ScoreRecordRepository scoreRecordRepository,
                                   WeaknessProfileService weaknessProfileService,
                                   InterviewPlanService interviewPlanService,
                                   ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.interviewPlanRepository = interviewPlanRepository;
        this.scoreRecordRepository = scoreRecordRepository;
        this.weaknessProfileService = weaknessProfileService;
        this.interviewPlanService = interviewPlanService;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成一场面试的评估报告（结构化 JSON，直接由 Controller 序列化返回）。
     * 无副作用（只读 + 一次叙事 LLM 调用），可重复调用。
     *
     * @param sessionId   会话 id（报告对象：这场面试）
     * @param candidateId 候选人 id（跨会话薄弱点段落的数据维度）
     */
    public Map<String, Object> generate(String sessionId, String candidateId) {
        InterviewPlan plan = interviewPlanRepository.findBySessionId(sessionId).orElse(null);
        List<InterviewPlanService.PlanTopic> plannedTopics =
                plan == null ? List.of() : interviewPlanService.parseTopics(plan.getTopicsJson());
        List<ScoreRecord> records = scoreRecordRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // ── 数字：Java 算 ──
        Map<String, List<ScoreRecord>> byTopic = groupByTopic(records);
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<String> uncoveredTopics = new ArrayList<>();
        List<String> matchedTopicNames = new ArrayList<>();

        for (InterviewPlanService.PlanTopic pt : plannedTopics) {
            List<ScoreRecord> matched = takeMatched(pt.topic(), byTopic);
            if (matched.isEmpty()) {
                uncoveredTopics.add(pt.topic());          // 计划了、一道没考
                continue;
            }
            matchedTopicNames.add(pt.topic());
            coverage.add(coverageItem(pt.topic(), true, matched));
        }
        List<Map<String, Object>> outOfPlan = new ArrayList<>();
        for (Map.Entry<String, List<ScoreRecord>> e : byTopic.entrySet()) {   // 剩下的 = 计划外
            outOfPlan.add(coverageItem(e.getKey(), false, e.getValue()));
        }

        String crossSessionWeakness = weaknessProfileService.loadProfile(candidateId);

        // ── 叙事：LLM 写（失败降级为占位文本，报告结构不缺角）──
        Map<String, Object> statsForLlm = Map.of(
                "planSource", plan == null ? "无" : plan.getSource(),
                "coverage", coverage,
                "uncoveredTopics", uncoveredTopics,
                "outOfPlanTopics", outOfPlan);
        Conclusion conclusion = generateConclusion(statsForLlm);

        // ── 组装报告 JSON ──
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sessionId", sessionId);
        report.put("candidateId", candidateId);
        report.put("generatedAt", java.time.LocalDateTime.now().toString());
        report.put("plan", Map.of(
                "source", plan == null ? "无" : plan.getSource(),
                "topics", plannedTopics));
        report.put("coverage", coverage);
        report.put("uncoveredTopics", uncoveredTopics);
        report.put("outOfPlanTopics", outOfPlan);
        report.put("crossSessionWeakness", crossSessionWeakness);
        report.put("conclusion", conclusion);
        log.info("评估报告生成完成：sessionId={}, candidateId={}, 计划考点={}（未覆盖 {}）, 计划外={}",
                sessionId, candidateId, plannedTopics.size(), uncoveredTopics.size(), outOfPlan.size());
        return report;
    }

    /** 记录按 topic 分组（保持首次出现顺序，报告可读） */
    private Map<String, List<ScoreRecord>> groupByTopic(List<ScoreRecord> records) {
        Map<String, List<ScoreRecord>> byTopic = new LinkedHashMap<>();
        for (ScoreRecord r : records) {
            byTopic.computeIfAbsent(r.getTopic(), k -> new ArrayList<>()).add(r);
        }
        return byTopic;
    }

    /**
     * 从分组中取走与计划考点匹配的记录（精确 → 包含双级；包含是给存量自由文本 topic 的
     * best-effort，新数据的 topic 来自封闭词表，通常走精确）。
     * "取走"= 从 byTopic 移除，剩下的天然就是计划外考点。
     */
    private List<ScoreRecord> takeMatched(String plannedTopic, Map<String, List<ScoreRecord>> byTopic) {
        List<ScoreRecord> exact = byTopic.remove(plannedTopic);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, List<ScoreRecord>> e : byTopic.entrySet()) {
            if (e.getKey().contains(plannedTopic) || plannedTopic.contains(e.getKey())) {
                return byTopic.remove(e.getKey());
            }
        }
        return List.of();
    }

    /** 单个考点的覆盖项：聚合数字 + 证据链（题目/回答节选/得分/点评） */
    private Map<String, Object> coverageItem(String topic, boolean planned, List<ScoreRecord> matched) {
        double sum = 0, min = Double.MAX_VALUE;
        List<Map<String, Object>> evidences = new ArrayList<>();
        for (ScoreRecord r : matched) {
            sum += r.getScore();
            min = Math.min(min, r.getScore());
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("question", truncate(r.getQuestion(), QUESTION_EXCERPT_LEN));
            ev.put("answerExcerpt", truncate(r.getAnswer(), ANSWER_EXCERPT_LEN));
            ev.put("score", r.getScore());
            ev.put("feedback", truncate(r.getFeedback(), FEEDBACK_EXCERPT_LEN));
            evidences.add(ev);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("topic", topic);
        item.put("planned", planned);
        item.put("questionCount", matched.size());
        item.put("avgScore", Math.round(sum / matched.size() * 10) / 10.0);
        item.put("minScore", min);
        item.put("evidences", evidences);
        return item;
    }

    /** 结论生成：LLM 只解读给定统计；失败降级为占位文本并记 WARN（报告结构不缺角） */
    private Conclusion generateConclusion(Map<String, Object> statsForLlm) {
        String statsJson;
        try {
            statsJson = objectMapper.writeValueAsString(statsForLlm);
        } catch (Exception e) {
            statsJson = "{}";
        }
        String input = "【统计数据 JSON】\n" + statsJson;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                List<Message> messages = List.of(new SystemMessage(CONCLUSION_PROMPT), new UserMessage(input));
                ChatResponse response = chatModel.call(new Prompt(messages));
                String text = response.getResult().getOutput().getText();
                Conclusion c = objectMapper.readValue(LlmJson.extract(text), Conclusion.class);
                if (c.overallSummary() == null || c.overallSummary().isBlank()) {
                    throw new IllegalStateException("结论 overallSummary 为空");
                }
                return c;
            } catch (Exception e) {
                log.warn("报告结论生成失败（第 {}/{} 次）：{}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        return new Conclusion("总结生成失败，请直接参考覆盖矩阵数据。",
                "匹配度结论生成失败，请对照计划考点与实际覆盖数据。",
                List.of());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** LLM 撰写的结论三件套 */
    record Conclusion(String overallSummary, String matchConclusion, List<String> recommendations) {
    }

}
