package com.xiao.aiagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.dto.PaperDto;
import com.xiao.aiagent.dto.PlanTopic;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.entity.KnowledgeBase;
import com.xiao.aiagent.entity.QuestionBankItem;
import com.xiao.aiagent.repository.InterviewPlanRepository;
import com.xiao.aiagent.repository.KnowledgeBaseRepository;
import com.xiao.aiagent.repository.QuestionBankRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 组卷服务（阶段 12 新增）——知识库面试"考什么"的生产方，决策 1/2 的落点。
 *
 * 组卷 = 「容量校验 → 随机抽 N 道 → 生成卷子快照 + 考点词表 → 落 interview_plan」，
 * 全程零 LLM（背题自检的诉求是"从集合里取样"，与语义相似度无关，一句 SQL 即可）。
 * 与资料模式的 InterviewPlanService（LLM 规划）是并行的两条生产路径，产物同为
 * interview_plan：三个消费方（面试官/评估官/报告）对两种模式无感知。
 *
 * 幂等与并发：一场会话一张卷子（uk_plan_session 兜底）——
 *   - 已有带卷子的计划 → 直接返回（同一张卷子，不重抽）；
 *   - 并发组卷撞唯一约束 → 读回先落库的一方（与 ensurePlan/ensureReport 同一手法）。
 *
 * 显式拒绝（不静默降级，决策 7）：容量不足直接失败并给出可用题数，
 * "有多少出多少"等于欺骗用户的选择——参考项目把这条当显式保证。
 */
@Service
public class PaperComposeService {

    private static final Logger log = LoggerFactory.getLogger(PaperComposeService.class);

    /** 组卷题数默认值与允许区间（决策 6/待确认 6：默认 20，允许 5~50） */
    public static final int DEFAULT_QUESTION_COUNT = 20;
    public static final int MIN_QUESTION_COUNT = 5;
    public static final int MAX_QUESTION_COUNT = 50;

    /** 考点短名长度上限：score_record.topic 是 varchar(64)（风险 5 的硬约束） */
    static final int TOPIC_MAX_LEN = 64;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final QuestionBankRepository questionBankRepository;
    private final InterviewPlanRepository interviewPlanRepository;
    private final ObjectMapper objectMapper;

    public PaperComposeService(KnowledgeBaseRepository knowledgeBaseRepository,
                               QuestionBankRepository questionBankRepository,
                               InterviewPlanRepository interviewPlanRepository,
                               ObjectMapper objectMapper) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.questionBankRepository = questionBankRepository;
        this.interviewPlanRepository = interviewPlanRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 为一场知识库面试组卷并落库（source=KNOWLEDGE）。
     * 卷子与考点词表是同一批题目的两种投影：paper_json 给面试官/评分对照，
     * topics_json 给评估封闭词表与报告覆盖矩阵——评估与报告因此零改动。
     *
     * @param kbId          知识库 id（必须存在）
     * @param questionCount 题数：null/≤0 用默认 20；超出 5~50 直接拒绝
     * @param sessionId     会话 id（一场一张卷子，幂等）
     * @param candidateId   练习者 id（与资料模式同口径，跨会话薄弱点聚合的身份键）
     */
    @Transactional
    public InterviewPlan compose(Long kbId, Integer questionCount, String sessionId, String candidateId) {
        int count = normalizeCount(questionCount);

        // 幂等：已有带卷子的计划 → 返回同一张（重开页面/重复入场不重抽，出题口径稳定）
        Optional<InterviewPlan> existing = interviewPlanRepository.findBySessionId(sessionId);
        if (existing.isPresent()) {
            InterviewPlan plan = existing.get();
            if (plan.getPaperJson() != null && !plan.getPaperJson().isBlank()) {
                log.info("会话已有卷子，组卷幂等返回：sessionId={}, planId={}", sessionId, plan.getId());
                return plan;
            }
            throw new IllegalArgumentException(
                    "该会话已存在面试计划，无法重新组卷：sessionId=" + sessionId);
        }

        KnowledgeBase kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在或已删除：id=" + kbId));

        // 容量校验：不足直接拒绝并给出可用题数（不静默降级）
        int capacity = questionBankRepository.countByKbIdAndStatus(kbId, QuestionBankItem.STATUS_ACTIVE);
        if (capacity < count) {
            throw new IllegalArgumentException("该题库共 " + capacity + " 道可用题，最多可选 "
                    + capacity + " 道，无法组 " + count + " 道题的卷子，请减少题数或补充题库");
        }

        List<QuestionBankItem> drawn = questionBankRepository.drawRandom(
                kbId, QuestionBankItem.STATUS_ACTIVE, count);
        if (drawn.size() < count) {
            // 防御：计数与抽题之间题库被删题（单机自用窗口极小，但残卷绝不能落库）
            throw new IllegalStateException("组卷异常：可用题数 " + capacity + " 但实际只抽到 "
                    + drawn.size() + " 道，疑似题库被并发修改，请重试：sessionId=" + sessionId);
        }

        String paperJson = writeJson(buildPaper(kb.getName(), drawn));
        String topicsJson = writeJson(buildTopics(drawn));

        InterviewPlan plan = new InterviewPlan(sessionId, candidateId, topicsJson,
                InterviewPlanService.SOURCE_KNOWLEDGE, LocalDateTime.now());
        plan.setPaperJson(paperJson);
        try {
            InterviewPlan saved = interviewPlanRepository.save(plan);
            log.info("组卷完成：sessionId={}, kbId={}, 题数={}, 考点数={}",
                    sessionId, kbId, count, parseTopicsCount(topicsJson));
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 并发兜底：双开标签页同时入场，两边都查到"无卷子"各抽一卷；
            // uk_plan_session 拦下后落库的一方，读回先落库的那份返回（谁先谁赢）
            InterviewPlan winner = interviewPlanRepository.findBySessionId(sessionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "撞唯一约束但查不到已有卷子，疑似约束定义与查询条件不一致：sessionId=" + sessionId, e));
            log.info("并发组卷撞唯一约束，读回已有卷子：planId={}, sessionId={}", winner.getId(), sessionId);
            return winner;
        }
    }

    /** 题数归一：缺省用默认；超出 5~50 直接拒绝（缺省是"没表达"，越界是"表达错了"） */
    private static int normalizeCount(Integer questionCount) {
        if (questionCount == null || questionCount <= 0) {
            return DEFAULT_QUESTION_COUNT;
        }
        if (questionCount < MIN_QUESTION_COUNT || questionCount > MAX_QUESTION_COUNT) {
            throw new IllegalArgumentException("组卷题数须在 " + MIN_QUESTION_COUNT + "~"
                    + MAX_QUESTION_COUNT + " 之间，收到：" + questionCount);
        }
        return questionCount;
    }

    private PaperDto buildPaper(String kbName, List<QuestionBankItem> drawn) {
        List<PaperDto.PaperQuestion> questions = new ArrayList<>(drawn.size());
        for (int i = 0; i < drawn.size(); i++) {
            QuestionBankItem q = drawn.get(i);
            questions.add(new PaperDto.PaperQuestion(i + 1, q.getStem(), q.getReferenceAnswer(),
                    q.getCategory(), q.getId()));
        }
        return new PaperDto(kbName, questions);
    }

    /** 考点词表：每道题一个 topic（规范化短名，去重、≤64 字符），评估/报告按同一词表封闭 */
    private List<PlanTopic> buildTopics(List<QuestionBankItem> drawn) {
        Set<String> seen = new HashSet<>();
        List<PlanTopic> topics = new ArrayList<>(drawn.size());
        for (QuestionBankItem q : drawn) {
            topics.add(new PlanTopic(deriveTopic(q.getStem(), seen), "中", "基础"));
        }
        return topics;
    }

    /**
     * 题目正文 → 考点短名（封闭词表的成员，全程 ≤64 字符）。
     * 规范化：取第一行 → 去尾部标点 → 截断；撞名加 "-2/-3" 序号后缀（先收窄主干再拼，总长仍 ≤64）。
     * 包私有 static：同包直测（风险 5 要求单测校验长度与去重）。
     */
    static String deriveTopic(String stem, Set<String> seen) {
        String base = stem == null ? "" : stem.strip();
        int newline = base.indexOf('\n');
        if (newline >= 0) {
            base = base.substring(0, newline).strip();
        }
        base = base.replaceAll("[？?。.，,：:；;！!、\\s]+$", "");
        if (base.isEmpty()) {
            base = "未命名考点";
        }
        String candidate = base.length() > TOPIC_MAX_LEN ? base.substring(0, TOPIC_MAX_LEN) : base;
        if (seen.add(candidate)) {
            return candidate;
        }
        for (int i = 2; ; i++) {
            String suffix = "-" + i;
            String truncated = base.substring(0, Math.min(base.length(), TOPIC_MAX_LEN - suffix.length())) + suffix;
            if (seen.add(truncated)) {
                return truncated;
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 卷子/词表都是纯数据（无循环引用），序列化失败只能是程序错误，不该静默降级
            throw new IllegalStateException("组卷数据序列化失败", e);
        }
    }

    /** 落库日志用：数一下词表条数（解析失败不阻塞，日志记 0 即可） */
    private int parseTopicsCount(String topicsJson) {
        try {
            PlanTopic[] arr = objectMapper.readValue(topicsJson, PlanTopic[].class);
            return arr.length;
        } catch (Exception e) {
            return 0;
        }
    }
}
