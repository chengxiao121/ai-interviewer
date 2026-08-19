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
    public ScoreRecord(String sessionId, String topic, String question, String answer,
                       Double score, String feedback, LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.topic = topic;
        this.question = question;
        this.answer = answer;
        this.score = score;
        this.feedback = feedback;
        this.createdAt = createdAt;
    }

}
