package com.xiao.aiagent.controller;

import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试会话绑定查询接口（阶段 8 新增）——只读。
 *
 * 消费方：前端会话管理页（卡片上显示候选人）、聊天页（刷新后恢复"已入场"状态
 * 与候选人展示）。绑定本身在聊天入口（AssistantController）创建，这里不提供写接口——
 * 入场是聊天动作的一部分，独立写接口会造成"绑了资料没聊天"的悬挂状态。
 */
@RestController
@RequestMapping("/api/interview-sessions")
public class InterviewSessionController {

    private final InterviewSessionRepository repository;

    public InterviewSessionController(InterviewSessionRepository repository) {
        this.repository = repository;
    }

    /** 全部面试会话绑定（按入场时间倒序） */
    @GetMapping
    public List<InterviewSessionDto> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(InterviewSessionController::toDto)
                .toList();
    }

    /** 按会话查绑定（404 = 未入场，前端据此判断是否要展示入场表单） */
    @GetMapping("/{sessionId}")
    public ResponseEntity<InterviewSessionDto> get(@PathVariable String sessionId) {
        return repository.findBySessionId(sessionId)
                .map(InterviewSessionController::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static InterviewSessionDto toDto(InterviewSession session) {
        return new InterviewSessionDto(session.getSessionId(), session.getCandidateId(),
                session.materialIds(), session.getCreatedAt().toString());
    }

    /** 会话绑定 DTO */
    public record InterviewSessionDto(String sessionId, String candidateId,
                                      List<String> materialIds, String createdAt) {
    }

}
