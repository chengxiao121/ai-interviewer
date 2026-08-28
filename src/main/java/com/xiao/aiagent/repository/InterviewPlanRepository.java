package com.xiao.aiagent.repository;

import com.xiao.aiagent.entity.InterviewPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 面试计划仓储（阶段 5.1 新增）。
 *
 * 这里定义的方法 = "一场面试一份计划"契约的访问接口：
 *   - findBySessionId：规划/面试官/评估官/报告四方共用的读取入口（ensurePlan 幂等的关键）；
 *   - existsBySessionId：留给"有没有计划"的廉价判断（当前主要走 findBySessionId 顺带判空）。
 */
public interface InterviewPlanRepository extends JpaRepository<InterviewPlan, Long> {

    /** 取某场面试的计划（一场一份；没有则 Optional.empty，由 InterviewPlanService 决定是否生成） */
    Optional<InterviewPlan> findBySessionId(String sessionId);

    /** 该会话是否已有计划 */
    boolean existsBySessionId(String sessionId);

}
