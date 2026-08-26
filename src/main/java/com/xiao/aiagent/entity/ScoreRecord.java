package com.xiao.aiagent.entity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单题评分记录，对应 PostgreSQL score_record 表。
 * 面试官 Agent 每点评完一道题，通过 scoreRecord 工具落库一条，
 * 供 questionStats 工具做薄弱考点聚合统计。
 */
@Entity
@Table(name = "score_record")
@Data
@NoArgsConstructor
public class ScoreRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 id（即 chat_memory_conversation_id），按会话隔离评分记录 */
    @Column(nullable = false, length = 64)
    private String sessionId;

    /**
     * 候选人 id（4.3 新增）：同一求职者跨多场面试共用，用于【跨会话】薄弱点聚合。
     * 与 sessionId 的区别：sessionId 是"这场面试"的上下文隔离键，candidateId 是"这个人"的身份键，
     * 一个候选人可有多场会话（一对多），两键各司其职同时落库。
     * 不设 NOT NULL：存量评分记录没有该列的值，ddl-auto=update 直接加 NOT NULL 列会被 PostgreSQL
     * 以"存量行为 NULL"拒绝；新建记录一定写入（工具侧兜底默认值），老数据的 NULL 在查询时用 COALESCE 归到 "default"。
     */
    @Column(length = 64)
    private String candidateId;

    /** 考点（如 Java并发 / Redis持久化 / MySQL索引），用于按考点聚合统计薄弱点 */
    @Column(nullable = false, length = 64)
    private String topic;

    /** 题目内容 */
    @Column(nullable = false, length = 2000)
    private String question;

    /** 求职者的回答 */
    @Column(nullable = false, length = 4000)
    private String answer;

    /** 评分（0~10，整数或一位小数） */
    @Column(nullable = false)
    private Double score;

    /** 面试官点评 / 改进建议 */
    @Column(length = 2000)
    private String feedback;

    /** 评分时间（服务端落库时生成，不依赖 LLM 传时间） */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 全参构造（@Data 不生成） */
    public ScoreRecord(String sessionId, String candidateId, String topic, String question, String answer,
                       Double score, String feedback, LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.candidateId = candidateId;
        this.topic = topic;
        this.question = question;
        this.answer = answer;
        this.score = score;
        this.feedback = feedback;
        this.createdAt = createdAt;
    }

}
