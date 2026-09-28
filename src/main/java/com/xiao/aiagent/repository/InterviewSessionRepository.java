package com.xiao.aiagent.repository;

import com.xiao.aiagent.entity.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 面试入场绑定仓库（阶段 8 新增）——interview_session 表的访问层。
 */
public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    /** 按会话查绑定（聊天入口判断"是否已入场"的依据） */
    Optional<InterviewSession> findBySessionId(String sessionId);

    /** 全部绑定（按入场时间倒序）——会话管理页展示候选人用 */
    List<InterviewSession> findAllByOrderByCreatedAtDesc();

    /** 清空会话时级联删绑定（Derived delete 需要事务） */
    @Transactional
    void deleteBySessionId(String sessionId);

}
