package com.xiao.aiagent.entity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 题库文档元数据，对应 PostgreSQL knowledge_document 表
 * 启动时用它做幂等比对（内容哈希 + 块数不变则跳过，避免重复入库）
 */
@Entity
@Table(name = "knowledge_document")
@Data
@NoArgsConstructor
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 文件名（含扩展名），全局唯一 */
    @Column(nullable = false, unique = true)
    private String fileName;

    /** 该文档分块数量 */
    @Column(nullable = false)
    private Integer chunkCount;

    /** 文件内容 SHA-256 哈希，内容不变则跳过入库 */
    @Column(nullable = false, length = 64)
    private String contentHash;

    /** 入库状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeDocStatus status;

    /** 数据来源：EXTERNAL / CLASSPATH */
    @Column(nullable = false, length = 20)
    private String source;

    /** 最近一次入库时间 */
    @Column(nullable = false)
    private LocalDateTime ingestedAt;

    /** 全参构造（@Data 不生成） */
    public KnowledgeDocument(String fileName, Integer chunkCount, String contentHash,
                             KnowledgeDocStatus status, String source, LocalDateTime ingestedAt) {
        this.fileName = fileName;
        this.chunkCount = chunkCount;
        this.contentHash = contentHash;
        this.status = status;
        this.source = source;
        this.ingestedAt = ingestedAt;
    }

}
