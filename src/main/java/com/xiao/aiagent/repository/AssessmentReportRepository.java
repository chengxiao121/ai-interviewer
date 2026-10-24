package com.xiao.aiagent.repository;

import com.xiao.aiagent.entity.AssessmentReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssessmentReportRepository extends JpaRepository<AssessmentReport, Long> {

    /**
     * 查某会话的已落库报告（uk_report_session 保证最多一份）。
     * 报告读取路径的缓存命中入口：命中即跳过叙事 LLM 调用，直接返回库内 JSON。
     */
    Optional<AssessmentReport> findBySessionId(String sessionId);
}
