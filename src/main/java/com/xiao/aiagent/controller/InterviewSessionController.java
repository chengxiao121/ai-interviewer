package com.xiao.aiagent.controller;

import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import com.xiao.aiagent.services.MaterialStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试会话绑定查询接口（阶段 8 新增）——只读。
 *
 * 消费方：前端会话管理页（按候选人分组展示）、聊天页（刷新后恢复"已入场"状态
 * 与候选人展示）。绑定本身在聊天入口（AssistantController）创建，这里不提供写接口——
 * 入场是聊天动作的一部分，独立写接口会造成"绑了资料没聊天"的悬挂状态。
 *
 * DTO 附带简历/JD 文件名（阶段 9）：UUID 对用户无意义，卡片显示
 * "第 N 场 · 时间 · 用了哪份简历/JD"更像面试记录而不是数据库行。
 */
@RestController
@RequestMapping("/api/interview-sessions")
public class InterviewSessionController {

    private final InterviewSessionRepository repository;
    private final MaterialStoreService materialStoreService;

    public InterviewSessionController(InterviewSessionRepository repository,
                                      MaterialStoreService materialStoreService) {
        this.repository = repository;
        this.materialStoreService = materialStoreService;
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
                session.materialIds(), session.getCreatedAt().toString(), resumeFileName, jdFileName);
    }

    /** 会话绑定 DTO（resumeFileName/jdFileName 供会话卡片展示，可为 null） */
    public record InterviewSessionDto(String sessionId, String candidateId,
                                      List<String> materialIds, String createdAt,
                                      String resumeFileName, String jdFileName) {
    }

}
