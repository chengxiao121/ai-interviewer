package com.xiao.aiagent.entity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试计划，对应 PostgreSQL interview_plan 表（阶段 5.1 新增，评估系统改造的数据契约之一）。
 *
 * 作用：这是【"考什么"的范围契约】的物理载体——评估要可信，必须先声明考什么（评分基准），
 *   否则"覆盖率"无从定义。规划环节（InterviewPlanService）产出计划落库，
 *   三个消费方按同一份词表工作：
 *     ① 面试官：按计划考点与优先级出题（system 消息注入）；
 *     ② 评估官：评分归类的考点必须从计划词表中选（封闭词表，覆盖率可 join 的前提）；
 *     ③ 报告：计划 vs 实际的覆盖矩阵对照物（验证官的"基准"）。
 *
 * 为什么独立建表而不复用 code_profile（profileType 加个 PLAN）：
 *   生命周期与语义都不同——code_profile 存"资料的画像"（一份资料一条，随资料产生），
 *   interview_plan 存"一场面试的考核范围"（一场一条，先于出题生成）；计划不是任何资料的画像，
 *   混进 code_profile 会让面试官 loadCodeFacts 把计划也当资料注入。分表即分语义。
 *
 * 一场一份：sessionId 定位唯一计划。5.x 约定不做"会话中途换计划"——
 *   中途新考的领域按"计划外考点"如实进报告（诚实呈现优于静默改约）。
 *   唯一的例外是升级：source=USER 的临时计划在 JD 画像落库后被重生成（见 InterviewPlanService）。
 */
@Entity
@Table(name = "interview_plan")
@Data
@NoArgsConstructor
public class InterviewPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 id（即 chat_memory_conversation_id），一场面试一份计划 */
    @Column(nullable = false, length = 64)
    private String sessionId;

    /**
     * 候选人 id（与 score_record 同口径）：报告侧做跨会话对照时预留的身份维度。
     * 不设 NOT NULL：与 ScoreRecord.candidateId 同理，存量兼容优先，新写入一定带值。
     */
    @Column(length = 64)
    private String candidateId;

    /**
     * 考点计划 JSON 全文（数据契约本体），结构：
     *   [{"topic":"线程池参数","priority":"高","difficulty":"进阶"}, ...]
     * topic 字符串即主键语义：评估落库、聚合统计、报告覆盖矩阵全部用这批字符串精确匹配
     * （考点词表封闭，见类注释②）；priority（高/中/低）决定出题顺序，difficulty（基础/进阶/挑战）
     * 是难度档，供面试官按档出题、评估后调档。
     * 存整段 JSON 而非拆列：考点数量可变（3~6 个），与 factsJson 同一权衡。
     */
    @Column(nullable = false, columnDefinition = "text")
    private String topicsJson;

    /**
     * 计划来源：JD=依据 JD 考察矩阵生成（评分基准）；USER=仅依据用户消息推断（临时计划）。
     * 为什么区分：综合面试首轮分析官还没跑完，JD 画像不在库，只能先按用户消息出临时计划
     * 保证第一题就有基准；JD 画像落库后 ensurePlan 会把 USER 计划升级重生成（InterviewPlanService）。
     */
    @Column(nullable = false, length = 16)
    private String source;

    /** 生成/升级时间（服务端生成，不依赖 LLM 传时间，与 ScoreRecord/CodeProfile 一致） */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 全参构造（@Data 不生成） */
    public InterviewPlan(String sessionId, String candidateId, String topicsJson, String source, LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.candidateId = candidateId;
        this.topicsJson = topicsJson;
        this.source = source;
        this.createdAt = createdAt;
    }

}
