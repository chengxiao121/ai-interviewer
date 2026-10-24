package com.xiao.aiagent.service;

import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;

/**
 * 面试会话生命周期服务（阶段 11 新增）——status 状态机的唯一写入口。
 *
 * 为什么单独成服务而不是散在 Controller/基类里：
 *   「结束」是跨层共享的判定（聊天管道每轮注入要用、报告门禁要用、结束接口要用），
 *   收口一处，状态常量与"未入场"的口径才不会漂移。
 *
 * 状态只有两档：IN_PROGRESS（入场默认）→ FINISHED（用户显式结束）。单向、不可逆
 * （要再练开新会话，与"绑定不可变、计划不做中途换"同一约定）；重复 finish 幂等返回。
 */
@Service
public class InterviewLifecycleService {

    private final InterviewSessionRepository repository;

    public InterviewLifecycleService(InterviewSessionRepository repository) {
        this.repository = repository;
    }

    /** 会话是否已结束（无绑定的会话视为未结束——老数据/未入场会话没有生命周期可言） */
    public boolean isFinished(String sessionId) {
        return repository.findBySessionId(sessionId)
                .map(s -> InterviewSession.STATUS_FINISHED.equals(s.getStatus()))
                .orElse(false);
    }

    /**
     * 结束面试：status → FINISHED。幂等（重复结束直接返回，不报错不重复写）。
     *
     * @throws IllegalArgumentException 绑定不存在（404 语义：该会话从未入场）——
     *                                  用户的错，走全局异常处理 400 透传
     */
    public InterviewSession finish(String sessionId) {
        InterviewSession session = repository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或尚未入场，无法结束面试"));
        if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
            return session;
        }
        session.setStatus(InterviewSession.STATUS_FINISHED);
        return repository.save(session);
    }

}
