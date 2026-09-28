package com.xiao.aiagent.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 面试入场绑定（阶段 8 新增），对应 PostgreSQL interview_session 表。
 *
 * 解决的问题（阶段 7 遗留）：上传的 materialIds 只跟着那一条消息走一次，
 * 会话与"谁在面、用什么资料"没有持久关系——刷新页面就丢，候选人身份也无法确定。
 *
 * 本实体是【面试入场】的落库形态：一条绑定 = sessionId → (candidateId, materialIds)。
 *   - candidateId：候选人姓名（用户入场时手填，归一化后落库），评分/薄弱点跨会话聚合的身份键；
 *     两份简历配两个姓名 = 两个互不干扰的候选人。
 *   - materialIds：本场面试绑定的资料（简历必选，JD 可选）。绑定后该会话所有消息
 *     自动走资料流水线，后续请求只带 sessionId，资料全文不重复进消息。
 *
 * 一场一份：sessionId 唯一。绑定不可变（不支持会话中途换简历——要换开新面试，
 * 与 interview_plan 的"不做中途换计划"同一约定）。
 * 生命周期：入场首轮对话时创建（AssistantController），清空会话时级联删除（MemoryController）。
 */
@Entity
@Table(name = "interview_session")
@Data
@NoArgsConstructor
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 id（唯一键，一场面试一条绑定） */
    @Column(nullable = false, unique = true, length = 64)
    private String sessionId;

    /**
     * 候选人 id（= 归一化后的候选人姓名）。与 score_record/interview_plan 同口径，
     * 是"跨会话薄弱点聚合"的身份维度；同姓名同简历 = 同一候选人身份延续。
     */
    @Column(nullable = false, length = 64)
    private String candidateId;

    /**
     * 绑定资料 id 列表，逗号连接存储（RESUME-xxx,JD-yyy）。
     * 逗号是安全的分隔符：materialId 格式为 类型前缀+32位十六进制，本身不含逗号。
     * 简历必在（入场强制），JD 可选。
     */
    @Column(nullable = false, columnDefinition = "text")
    private String materials;

    /** 入场时间（服务端生成） */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 全参构造（@Data 不生成） */
    public InterviewSession(String sessionId, String candidateId, List<String> materialIds) {
        this.sessionId = sessionId;
        this.candidateId = candidateId;
        this.materials = String.join(",", materialIds);
        this.createdAt = LocalDateTime.now();
    }

    /** 还原资料 id 列表 */
    public List<String> materialIds() {
        return Arrays.stream(materials.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

}
