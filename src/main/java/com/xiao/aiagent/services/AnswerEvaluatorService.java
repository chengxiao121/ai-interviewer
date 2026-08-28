package com.xiao.aiagent.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.entity.ScoreRecord;
import com.xiao.aiagent.repository.ScoreRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 答案评估服务（阶段 5.3 新增）——会话内评估闭环的发动机。
 *
 * 定位：与 InterviewPlanService 同类的【带 LLM 的普通函数】——输入固定（上一问+本轮回答）、
 * 输出固定（结构化评估 JSON），无工具循环，不配 ReactAgent（判据见《阶段5-评估系统改造计划》§0.3）。
 *
 * 为什么把它从面试官身上拆出来（三个结构性理由）：
 *   1. 考官与评分分离：出题的上下文会污染打分（面试官人设倾向鼓励），独立评估更客观、可复现；
 *   2. 结构性修掉风险 23（flash"用嘴打分不落库"）：旧链路靠面试官在长对话中间"自觉调 scoreRecord
 *      工具"落库，是概率事件；现在评分落库由本服务代码直接执行——漂移面从"长对话中的一次
 *      概率性工具调用"缩成"独立单轮结构化输出 + 确定性落库"；
 *   3. 反馈周期从"跨会话"缩到"跨单题"：评估结果注入下一轮上下文，面试官当场调整难度/方向
 *      （4.3 的薄弱点回顾要等下一场面试才生效，本服务让它在 30 秒后生效）。
 *
 * 触发方式：InterviewAssistant.chat() 每轮第 1 步确定性调用（不靠模型自觉）。
 * 是否评分由 LLM 在结构化输出里自门控（isAnswer）——比"用正则猜这句是不是答案"可靠：
 * 寒暄/反问/请求换题 → isAnswer=false → 不落库不注入；真作答 → 评分落库 + 产注入文本。
 *
 * 考点词表封闭：topic 必须取自面试计划词表（resolveTopic 三级校验：精确 → 包含 → 计划外），
 * 这是评估报告"覆盖矩阵"能做精确 join 的前提。无计划时退化为自由归类（等同 4.3 行为）。
 */
@Service
public class AnswerEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluatorService.class);

    /** 解析失败重试次数（与 InterviewPlanService 同策略：一次重试换可观成功率） */
    private static final int MAX_ATTEMPTS = 2;
    /** prompt 经济性截断：上一问取尾部（题目通常在后半段），回答取头部 */
    private static final int MAX_QUESTION_PROMPT_LEN = 1500;
    private static final int MAX_ANSWER_PROMPT_LEN = 3000;
    /** 落库列长防御（score_record 列定义：question 2000 / answer 4000 / feedback 2000 / topic 64） */
    private static final int MAX_QUESTION_COL_LEN = 2000;
    private static final int MAX_ANSWER_COL_LEN = 4000;
    private static final int MAX_FEEDBACK_COL_LEN = 2000;
    private static final int MAX_TOPIC_COL_LEN = 64;
    /** 无法归入计划词表时的 topic 前缀（报告按此前缀识别"计划外考点"） */
    private static final String OUT_OF_PLAN_PREFIX = "计划外";

    /**
     * 评估 prompt——评分标准从面试官 SYSTEM_PROMPT 迁移至此（评分职责唯一归属）。
     * "是否作答"的自门控也约定在这里，代替脆弱的正则启发式。
     */
    private static final String EVALUATOR_PROMPT = """
            你是面试评估官，独立于面试官，只负责评估"候选人对上一题的回答"。
            输入包含：【考点词表】【上一问】（面试官最近一次的发言，含其最新提出的题目）【本轮回答】（候选人刚说的话）。
            任务：
            1. 判断本轮回答是否是对上一问中题目的作答（寒暄、反问、求助、请求换题、与题目无关的闲聊都算"不是作答"）；
            2. 是作答则按 0~10 分评分：0-3 完全不会或严重错误 / 4-6 部分正确、有缺失 / 7-9 较完整、有小瑕疵 / 10 准确全面；
            3. 考点归类：topic 必须【逐字】取自考点词表中的某一项；确实无法归入词表时输出"计划外"；
            4. question 字段：从【上一问】中提取被回答的那道题的核心题目文本（不要把点评部分带进去）；
            5. 给出简短点评（strengths 亮点 / gaps 薄弱）与下一题建议（nextSuggestion，如"该考点换更基础角度再问""可进入下一考点"）。
            只输出 JSON 对象，不要任何解释文字或围栏：
            {"isAnswer":true,"topic":"线程池参数","question":"…","score":7.5,"strengths":"…","gaps":"…","nextSuggestion":"…"}
            isAnswer 为 false 时，其余字段一律输出空串或 0。
            """;

    /** LLM 结构化评估结果（Jackson 直接反序列化目标） */
    record Evaluation(Boolean isAnswer, String topic, String question, Double score,
                      String strengths, String gaps, String nextSuggestion) {
    }

    /**
     * 对外评估结果：
     *   answered      —— 本轮是否产生了评分落库；
     *   injectionText —— 注入面试官的【上一题评估】文本（空串 = 无评估，调用方跳过注入）。
     */
    public record EvaluationOutcome(boolean answered, String injectionText) {
    }

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final InterviewPlanService interviewPlanService;
    private final ScoreRecordRepository scoreRecordRepository;
    private final ObjectMapper objectMapper;

    public AnswerEvaluatorService(ChatModel chatModel,
                                  ChatMemory chatMemory,
                                  InterviewPlanService interviewPlanService,
                                  ScoreRecordRepository scoreRecordRepository,
                                  ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.interviewPlanService = interviewPlanService;
        this.scoreRecordRepository = scoreRecordRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 评估本轮回答：是作答 → 评分落库并返回注入文本；否则/失败 → 空结果（面试不阻塞）。
     * 必须在面试官出题【之前】调用（评估结论要影响本题的难度与方向，见 InterviewAssistant.chat 时序）。
     *
     * @param userMessage 候选人本轮消息（即"本轮回答"）
     * @param sessionId   会话 id（短期窗口取上一问 / 评分落库归属）
     * @param candidateId 候选人 id（评分落库，跨会话薄弱点聚合维度）
     * @param plan        本场面试计划（考点词表来源；可为 null → 自由归类）
     */
    public EvaluationOutcome evaluateTurn(String userMessage, String sessionId, String candidateId, InterviewPlan plan) {
        String lastAssistant = findLastAssistantMessage(sessionId);
        if (lastAssistant == null) {
            return new EvaluationOutcome(false, "");    // 首轮：还没有"上一问"，无从评估
        }

        List<InterviewPlanService.PlanTopic> vocabulary = plan == null
                ? List.of()
                : interviewPlanService.parseTopics(plan.getTopicsJson());
        String input = "【考点词表】" + (vocabulary.isEmpty()
                ? "（本场无计划，按回答内容自由归类考点）"
                : vocabulary.stream().map(InterviewPlanService.PlanTopic::topic).toList())
                + "\n\n【上一问】\n" + truncateTail(lastAssistant, MAX_QUESTION_PROMPT_LEN)
                + "\n\n【本轮回答】\n" + truncateHead(userMessage, MAX_ANSWER_PROMPT_LEN);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Evaluation eval = callEvaluator(input);
                if (eval == null || !Boolean.TRUE.equals(eval.isAnswer())) {
                    log.info("评估判定非作答，跳过评分：sessionId={}", sessionId);
                    return new EvaluationOutcome(false, "");
                }
                if (eval.score() == null || eval.topic() == null || eval.topic().isBlank()) {
                    throw new IllegalStateException("评估输出缺少 score/topic");
                }
                String topic = resolveTopic(eval.topic(), vocabulary);
                String question = eval.question() == null || eval.question().isBlank()
                        ? truncateHead(lastAssistant, MAX_QUESTION_COL_LEN)
                        : truncateHead(eval.question(), MAX_QUESTION_COL_LEN);
                persist(sessionId, candidateId, topic, question, userMessage, eval);
                String injection = buildInjection(topic, eval);
                log.info("评估落库成功：sessionId={}, candidateId={}, topic={}, score={}",
                        sessionId, candidateId, topic, eval.score());
                return new EvaluationOutcome(true, injection);
            } catch (Exception e) {
                log.warn("答案评估失败（第 {}/{} 次）：sessionId={}, {}",
                        attempt, MAX_ATTEMPTS, sessionId, e.getMessage());
            }
        }
        // 降级：评估是增强项不是阻塞项——失败只丢本次评分，面试照常（计划文档 §8.1）
        log.warn("答案评估最终失败，本轮不评分不注入：sessionId={}", sessionId);
        return new EvaluationOutcome(false, "");
    }

    /** 从短期窗口取最近一条助手消息（含最新题目；点评+新题混合也交给 LLM 自己提取题目部分） */
    private String findLastAssistantMessage(String sessionId) {
        List<Message> history = chatMemory.get(sessionId);
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof AssistantMessage assistant && assistant.getText() != null && !assistant.getText().isBlank()) {
                return assistant.getText();
            }
        }
        return null;
    }

    private Evaluation callEvaluator(String input) throws Exception {
        List<Message> messages = List.of(new SystemMessage(EVALUATOR_PROMPT), new UserMessage(input));
        ChatResponse response = chatModel.call(new Prompt(messages));
        String text = response.getResult().getOutput().getText();
        return objectMapper.readValue(LlmJson.extract(text), Evaluation.class);
    }

    /**
     * 考点三级校验（封闭词表，报告覆盖矩阵可 join 的前提）：
     * 精确匹配 → 包含匹配归一到词表措辞 → 兜底"计划外：<原文>"。词表为空（无计划）时自由归类。
     */
    private String resolveTopic(String topic, List<InterviewPlanService.PlanTopic> vocabulary) {
        String t = topic.trim();
        if (vocabulary.isEmpty()) {
            return truncateHead(t, MAX_TOPIC_COL_LEN);
        }
        for (InterviewPlanService.PlanTopic v : vocabulary) {
            if (v.topic().equals(t)) {
                return v.topic();
            }
        }
        for (InterviewPlanService.PlanTopic v : vocabulary) {
            if (t.contains(v.topic()) || v.topic().contains(t)) {
                return v.topic();
            }
        }
        return truncateHead(t.startsWith(OUT_OF_PLAN_PREFIX) ? t : OUT_OF_PLAN_PREFIX + "：" + t, MAX_TOPIC_COL_LEN);
    }

    /** 落库（幂等：同会话同题更新不新增，沿用 4.3 的 findBySessionIdAndQuestion 去重约定） */
    private void persist(String sessionId, String candidateId, String topic, String question,
                         String userMessage, Evaluation eval) {
        double score = Math.max(0, Math.min(10, eval.score()));
        String feedback = buildFeedback(eval);
        Optional<ScoreRecord> existing = scoreRecordRepository.findBySessionIdAndQuestion(sessionId, question);
        if (existing.isPresent()) {
            ScoreRecord record = existing.get();
            record.setCandidateId(candidateId);
            record.setTopic(topic);
            record.setAnswer(truncateHead(userMessage, MAX_ANSWER_COL_LEN));
            record.setScore(score);
            record.setFeedback(feedback);
            record.setCreatedAt(LocalDateTime.now());
            scoreRecordRepository.save(record);
            log.info("同题重复评分，已更新原记录：id={}, topic={}, score={}", record.getId(), topic, score);
            return;
        }
        scoreRecordRepository.save(new ScoreRecord(
                sessionId, candidateId, topic, question,
                truncateHead(userMessage, MAX_ANSWER_COL_LEN), score, feedback, LocalDateTime.now()));
    }

    /** 点评合并为 feedback 单列（score_record 只有一列点评：亮点+薄弱+建议拼接） */
    private String buildFeedback(Evaluation eval) {
        StringBuilder sb = new StringBuilder();
        if (eval.strengths() != null && !eval.strengths().isBlank()) {
            sb.append("亮点：").append(eval.strengths()).append(" ");
        }
        if (eval.gaps() != null && !eval.gaps().isBlank()) {
            sb.append("薄弱：").append(eval.gaps()).append(" ");
        }
        if (eval.nextSuggestion() != null && !eval.nextSuggestion().isBlank()) {
            sb.append("建议：").append(eval.nextSuggestion());
        }
        return sb.isEmpty() ? "无" : truncateHead(sb.toString(), MAX_FEEDBACK_COL_LEN);
    }

    /** 组装注入面试官的【上一题评估】文本（评估结论影响本题难度/方向的载体） */
    private String buildInjection(String topic, Evaluation eval) {
        StringBuilder sb = new StringBuilder("【上一题评估】考点：").append(topic)
                .append("；得分：").append(String.format("%.1f", eval.score())).append("/10");
        if (eval.strengths() != null && !eval.strengths().isBlank()) {
            sb.append("；亮点：").append(eval.strengths());
        }
        if (eval.gaps() != null && !eval.gaps().isBlank()) {
            sb.append("；薄弱：").append(eval.gaps());
        }
        if (eval.nextSuggestion() != null && !eval.nextSuggestion().isBlank()) {
            sb.append("；下题建议：").append(eval.nextSuggestion());
        }
        sb.append("。请向求职者自然转述这份点评，并按建议调整本题的难度与方向。");
        return sb.toString();
    }

    /** 头部截断（保长度上限） */
    private static String truncateHead(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 尾部截断（题目通常在助手消息后半段，丢头部点评保尾部题目） */
    private static String truncateTail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

}
