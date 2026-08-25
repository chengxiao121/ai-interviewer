package com.xiao.aiagent.entity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 代码分析事实清单，对应 PostgreSQL code_profile 表（阶段 4.1 新增）。
 *
 * 作用：这是【双 Agent 数据契约的物理载体】。
 *   CodeAnalyzerAgent（分析型）读候选人代码 → 产出结构化 JSON 事实清单 → 落到这张表；
 *   InterviewAssistant（对话型，面试官）出题时从这里按 sessionId 取清单，基于清单出题追问。
 *
 * 为什么落库而非内存传递（4.1 核心设计决策）：
 *   1. 同 sessionId 重开会话时，清单已在表里，面试官直接取，不重复跑分析（省 LLM 调用）；
 *   2. 分析是"一次性前置"、面试是"多轮持续"，落库让两者解耦，不要求同一时刻运行。
 *
 * 与 ScoreRecord 的区别：ScoreRecord 存"评估结果"（下游产物），
 *   code_profile 存"分析物料"（上游产物），一上一下构成闭环的两端。
 */
@Entity
@Table(name = "code_profile")
@Data
@NoArgsConstructor
public class CodeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 id（即 chat_memory_conversation_id），按会话隔离事实清单 */
    @Column(nullable = false, length = 64)
    private String sessionId;

    /** 分析的代码文件路径（同会话可能分析多个文件，每个文件一条记录） */
    @Column(nullable = false, length = 512)
    private String filePath;

    /**
     * 资料类型（阶段 4.2 新增）：CODE=代码 / JD=岗位描述 / RESUME=简历。
     * 为什么 JD/简历分析与代码分析共用一张表：三者是同构的——都是"读资料 → 产出结构化事实清单"，
     * 共用一张表，用本字段区分类型，面试官取清单时一次全取，无需三张表三套读写。
     * 列定义带默认值 'CODE'：存量旧数据（4.2 之前只有代码清单）自动归为 CODE，不炸迁移。
     */
    @Column(length = 16, columnDefinition = "varchar(16) default 'CODE'")
    private String profileType;

    /**
     * 事实清单 JSON 全文（数据契约本体）。
     * 结构由 CodeAnalyzerAgent 的 prompt 约定，典型字段：
     *   techStack / designPatterns / implementationDetails / riskPoints / followUpPoints
     * 存整段 JSON 而非拆列：AI 产出天然半结构化，风险点数量可变，固定列放不下。
     */
    @Column(nullable = false, columnDefinition = "text")
    private String factsJson;

    /** 分析时间（服务端落库时生成，不依赖 LLM 传时间，与 ScoreRecord 一致） */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 全参构造（@Data 不生成） */
    public CodeProfile(String sessionId, String filePath, String profileType, String factsJson, LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.filePath = filePath;
        this.profileType = profileType;
        this.factsJson = factsJson;
        this.createdAt = createdAt;
    }

}
