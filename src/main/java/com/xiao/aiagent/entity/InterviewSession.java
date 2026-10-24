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
 *
 * 模式（阶段 12 新增）：一场面试在入场时绑定两种互斥模式之一，绑定后不可切换。
 *   - MATERIAL：资料面试（原模式），题由模型基于简历 + JD 画像临场生成，materials 必在；
 *   - KNOWLEDGE：知识库面试，题从 kbId 指向的题库抽卷，materials 为空（无资料可绑）。
 * 为什么合表不拆表：两种模式本质都是"一场有始有终的对话"，共享生命周期状态机、
 * 报告门禁与结束动作；拆表会让评分、薄弱点聚合、报告、状态机全部写两套，收益为零。
 * 要分的是展示层（列表筛选、卡片信息、报告分叉），不是存储层。
 */
@Entity
@Table(name = "interview_session")
@Data
@NoArgsConstructor
public class InterviewSession {

    /** 会话状态：进行中（入场默认，可出题） */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** 会话状态：已结束（面试官只答疑不出题；评估报告仅此状态可看） */
    public static final String STATUS_FINISHED = "FINISHED";

    /** 面试模式：资料面试（默认，题由模型基于简历 + JD 画像生成） */
    public static final String MODE_MATERIAL = "MATERIAL";
    /** 面试模式：知识库面试（题从选定题库抽卷，materials 为空） */
    public static final String MODE_KNOWLEDGE = "KNOWLEDGE";

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
     * 简历必在（资料模式入场强制），JD 可选；知识库模式无资料 → 存空串（不是 NULL：
     * 空串让"无资料"与"未赋值"两种状态不必区分，读取侧少一个判空分支）。
     */
    @Column(columnDefinition = "text")
    private String materials;

    /** 入场时间（服务端生成） */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 面试模式（阶段 12 新增）：MATERIAL / KNOWLEDGE，入场时绑定、绑定后不可变。
     * 列有 DB 默认值 'MATERIAL'，存量会话升级后自动落到资料模式，行为与升级前一致。
     */
    @Column(nullable = false, length = 16)
    private String mode = MODE_MATERIAL;

    /**
     * 绑定的知识库 id（阶段 12 新增）：仅 KNOWLEDGE 模式有值，资料模式恒为 null。
     * 不设外键约束：知识库被删除后，历史会话仍要能展示与出报告（降级为不显示库名）。
     */
    @Column
    private Long kbId;

    /**
     * 会话生命周期状态（阶段 11 新增）：IN_PROGRESS（默认）/ FINISHED。
     * 把"何时结束"的决定权从模型手里收回来交给用户：结束是显式动作（finish 接口），
     * 不是模型自觉——结束后每轮注入"不出新题"提示，报告门禁以此为准。
     */
    @Column(nullable = false, length = 16)
    private String status = STATUS_IN_PROGRESS;

    /** 全参构造（@Data 不生成） */
    public InterviewSession(String sessionId, String candidateId, List<String> materialIds) {
        this.sessionId = sessionId;
        this.candidateId = candidateId;
        this.materials = String.join(",", materialIds);
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 还原资料 id 列表。
     * materials 为 null（知识库模式的历史数据或手工置空）时返回空列表——
     * 不抛 NPE：会话列表与入场校验都会遍历它，"没有资料"是合法状态不是异常。
     */
    public List<String> materialIds() {
        if (materials == null || materials.isBlank()) {
            return List.of();
        }
        return Arrays.stream(materials.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

}
