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
 * 知识库，对应 PostgreSQL knowledge_base 表（阶段 12 新增）。
 *
 * 一次上传 = 一个知识库：用户上传一份「【考点】+【参考答案】」格式的 txt/md，
 * 解析出的题目归属该库（见 QuestionBankItem）。"多个知识库"就是多个平级的题池，
 * 开场时选其中一个组卷——这替换掉了旧的"扫描目录灌向量库"方案：
 *   旧方案里两个来源（外部目录 + classpath 内置）会同时入库、内置题永远删不掉，
 *   且状态分散在磁盘文件 / PG 元数据表 / Redis 向量三处；新方案只在这里有一处状态。
 *
 * 原文不落盘：解析完题目即弃（面试时只读 question_bank，从不读原文）。
 * 需要重新解析 → 用户重新上传。省掉一整套对象存储（单机自用不引入额外容器）。
 *
 * questionCount 是冗余计数（= 该库 ACTIVE 题目数），为列表页免 join 直接展示；
 * 与题库实际行数可能短暂不一致，以题目表为准，展示用这里。
 */
@Entity
@Data
@NoArgsConstructor
// 名称唯一：知识库没有 id 以外的业务标识，列表与选择器都按名字认库，
// 重名会让用户（和组卷接口）分不清选的是哪个——名称就是它的自然键。
@Table(name = "knowledge_base",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_name", columnNames = {"name"}))
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识库名称（唯一，用户新建时手填；选择器与会话卡片都展示它） */
    @Column(nullable = false, length = 64)
    private String name;

    /** 描述（可选，用户备注这个库考什么） */
    @Column(length = 255)
    private String description;

    /** 题目数（ACTIVE 题目计数，列表展示用，非权威） */
    @Column(nullable = false)
    private Integer questionCount = 0;

    /** 来源文件名（上传时的原始文件名，溯源用；原文不保存） */
    @Column(length = 255)
    private String sourceFileName;

    /** 内容哈希（同名重复上传检测预留；本次仅落库展示，不做强制去重） */
    @Column(length = 64)
    private String contentHash;

    /** 创建时间（服务端生成） */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 全参构造（@Data 不生成） */
    public KnowledgeBase(String name, String description, Integer questionCount,
                         String sourceFileName, String contentHash, LocalDateTime createdAt) {
        this.name = name;
        this.description = description;
        this.questionCount = questionCount;
        this.sourceFileName = sourceFileName;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
    }
}
