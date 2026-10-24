package com.xiao.aiagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试评估报告的持久化形态，对应 PostgreSQL assessment_report 表（V2 迁移新增）。
 *
 * 设计：报告首次查看时由 AssessmentReportService 生成并落库（ensure 模式，
 * 与 interview_plan 的 ensurePlan 同一套先例），之后所有查看直接读库——
 * 跳过叙事 LLM 调用，省 token 且响应秒回。
 *
 * 快照语义：落库的是"生成时刻的快照"。跨会话薄弱点（crossSessionWeakness）
 * 段落不会随候选人后续新面试自动更新，需要重新生成（version+1）才会刷新——
 * 这是特性不是缺陷：报告可复现、可对账，数字部分（代码计算）永远稳定。
 *
 * report_json 存完整结构化 JSON（覆盖矩阵/证据链/结论三件套），前端报告页
 * 直接渲染；Markdown / PDF 是导出时的呈现格式，由前端转换，库里不存。
 */
@Entity
@Data
@NoArgsConstructor
// 唯一约束 uk_report_session：一场会话只保留最新一份报告。
// 作用：并发首次查看（双开标签页）两边都 miss 缓存各自生成时，
// 由数据库在最后一步兜底——后落库者撞约束，读回先落库者返回（谁先谁赢），
// 与 uk_plan_session / uk_score_session_question 是同一套并发防线。
@Table(name = "assessment_report",
        uniqueConstraints = @UniqueConstraint(name = "uk_report_session",
                columnNames = {"session_id"}))
public class AssessmentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 id（即 chat_memory_conversation_id），报告归属键：一场面试一份最新报告 */
    @Column(nullable = false, length = 64)
    private String sessionId;

    /** 候选人 id（生成报告时的请求口径，存档备查；理论上一场会话只属于一个候选人） */
    @Column(nullable = false, length = 64)
    private String candidateId;

    /** 完整报告 JSON（结构化字段见 AssessmentReportService.generate） */
    @Column(nullable = false, columnDefinition = "text")
    private String reportJson;

    /** 版本号：首次为 1，重新生成（force）时递增。只保留最新一份，不做历史版本列表 */
    @Column(nullable = false)
    private Integer version;

    /** 生成时间（落库时服务端时间，报告页展示用——快照的拍摄时刻） */
    @Column(nullable = false)
    private LocalDateTime generatedAt;

    /** 全参构造（@Data 不生成） */
    public AssessmentReport(String sessionId, String candidateId, String reportJson,
                            Integer version, LocalDateTime generatedAt) {
        this.sessionId = sessionId;
        this.candidateId = candidateId;
        this.reportJson = reportJson;
        this.version = version;
        this.generatedAt = generatedAt;
    }
}
