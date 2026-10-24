package com.xiao.aiagent.controller;

import com.xiao.aiagent.dto.InterviewSessionDto;
import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import com.xiao.aiagent.service.InterviewLifecycleService;
import com.xiao.aiagent.service.MaterialStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试会话绑定查询接口（阶段 8 新增）——只读。
 *
 * 消费方：前端会话管理页（按候选人分组展示）、聊天页（刷新后恢复"已入场"状态
 * 与候选人展示）。绑定本身在聊天入口（AssistantController）创建。
 *
 * 写接口只有 finish（阶段 11）：结束是会话生命周期动作（状态机 IN_PROGRESS→FINISHED），
 * 不是聊天消息——放这里语义最顺（操作的资源就是会话绑定本身）。独立于聊天通道，
 * 避免靠"发一条特殊消息"来隐式表达结束（那条消息会污染面试记忆与历史展示）。
 *
 * DTO 附带简历/JD 文件名（阶段 9）：UUID 对用户无意义，卡片显示
 * "第 N 场 · 时间 · 用了哪份简历/JD"更像面试记录而不是数据库行。
 * DTO 附带 status（阶段 11）：前端据此决定报告入口是否可用、显示"已结束"标签。
 */
@RestController
@RequestMapping("/api/interview-sessions")
public class InterviewSessionController {

    private final InterviewSessionRepository repository;
    private final MaterialStoreService materialStoreService;
    private final InterviewLifecycleService lifecycleService;

    public InterviewSessionController(InterviewSessionRepository repository,
                                      MaterialStoreService materialStoreService,
                                      InterviewLifecycleService lifecycleService) {
        this.repository = repository;
        this.materialStoreService = materialStoreService;
        this.lifecycleService = lifecycleService;
    }

    /** 全部面试会话绑定（按入场时间倒序） */
    @GetMapping
    public List<InterviewSessionDto> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    /** 按会话查绑定（404 = 未入场，前端据此判断是否要展示入场表单） */
    @GetMapping("/{sessionId}")
    public ResponseEntity<InterviewSessionDto> get(@PathVariable String sessionId) {
        return repository.findBySessionId(sessionId)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 结束面试（阶段 11）：status → FINISHED，幂等（重复结束原样返回）。
     * 前端流程：确认 → 调本接口 → 跳评估报告页（报告门禁以此状态为准）。
     * 未入场会话 → IllegalArgumentException → 全局异常处理 400 透传。
     */
    @PostMapping("/{sessionId}/finish")
    public InterviewSessionDto finish(@PathVariable String sessionId) {
        return toDto(lifecycleService.finish(sessionId));
    }

    /** DTO 组装：顺带把资料 id 翻译成文件名（读不到元数据的 id 静默跳过，不炸接口） */
    private InterviewSessionDto toDto(InterviewSession session) {
        String resumeFileName = null;
        String jdFileName = null;
        for (String id : session.materialIds()) {
            var meta = materialStoreService.findMeta(id).orElse(null);
            if (meta == null) {
                continue;
            }
            if ("RESUME".equals(meta.type()) && resumeFileName == null) {
                resumeFileName = meta.fileName();
            } else if ("JD".equals(meta.type()) && jdFileName == null) {
                jdFileName = meta.fileName();
            }
        }
        return new InterviewSessionDto(session.getSessionId(), session.getCandidateId(),
                session.materialIds(), session.getCreatedAt().toString(), resumeFileName, jdFileName,
                session.getStatus());
    }

}
