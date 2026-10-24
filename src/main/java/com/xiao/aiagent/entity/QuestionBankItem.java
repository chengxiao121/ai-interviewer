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
 * 题库中的一道题，对应 PostgreSQL question_bank 表（阶段 12 新增）。
 *
 * 题目是【实体】而不是"检索出来的文本块"——这是阶段 12 的关键形态变化：
 * 旧方案里题库只是向量库中的一堆文本块，没有"一道题"的边界，模型可以完全不看
 * 注入内容，也无法回答"还有几道没问""这次抽了哪些题"。有了题目实体才有
 * 组卷（抽 N 道不重复）、卷子快照、按题评分这些能力。
 *
 * 题目怎么来的：解析，不是 LLM 生成（决策 4）。题库文档本身就是
 * 「【考点】… + 【参考答案】…」空行分隔的格式，文档原文即题目，按块解析即可——
 * 零成本、结果确定、当场可验证。LLM 补分类/难度列为后续（阶段 8）。
 *
 * seq 保留文档内原题序：同一份文档重复解析时靠 (kb_id, seq) 唯一约束兜底，
 * 也让"题目预览"能按作者原本的顺序展示。
 */
@Entity
@Data
@NoArgsConstructor
// UNIQUE (kb_id, seq)：一个库内题序不重复，重复解析同一份文档不会把题写两遍
// （与 uk_plan_session / uk_report_session 同一套"数据库兜底幂等"手法）。
@Table(name = "question_bank",
        uniqueConstraints = @UniqueConstraint(name = "uk_qb_kb_seq", columnNames = {"kb_id", "seq"}))
public class QuestionBankItem {

    /** 题目状态：可用（参与组卷） */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 题目状态：归档（不参与组卷；暂不提供界面入口，为后续"下架坏题"预留） */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库 id（删除知识库时连带删除本库全部题目） */
    @Column(nullable = false)
    private Long kbId;

    /** 库内原题序（从 1 开始，按文档解析顺序） */
    @Column(nullable = false)
    private Integer seq;

    /** 题目正文（stem：题干，念给候选人听的那一段） */
    @Column(nullable = false, columnDefinition = "text")
    private String stem;

    /** 参考答案（文档自带；面试中给要点、评估时作对照基准） */
    @Column(nullable = false, columnDefinition = "text")
    private String referenceAnswer;

    /** 分类（从文档的 `##` 二级标题解析，无标题时为 null） */
    @Column(length = 64)
    private String category;

    /** 状态：ACTIVE（默认，可组卷）/ ARCHIVED（不参与组卷） */
    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    /** 入库时间（服务端生成） */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 全参构造（@Data 不生成） */
    public QuestionBankItem(Long kbId, Integer seq, String stem, String referenceAnswer,
                            String category, String status, LocalDateTime createdAt) {
        this.kbId = kbId;
        this.seq = seq;
        this.stem = stem;
        this.referenceAnswer = referenceAnswer;
        this.category = category;
        this.status = status;
        this.createdAt = createdAt;
    }
}
