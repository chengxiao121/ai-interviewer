package com.xiao.aiagent.controller;

import com.xiao.aiagent.services.AssessmentReportService;
import com.xiao.aiagent.tools.SessionKeys;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评估报告端点（阶段 5.4 新增）——《面试评估报告》的 HTTP 出口。
 *
 * 为什么独立端点而不走 /api/assistant/chat：
 *   报告是【交付物】（可下载、可第三方校验的结构化判定结果），不是对话流。
 *   接口层面区分"对话流（SSE 流式）"与"判定结果（同步 JSON）"，正是"评估系统 vs 聊天"
 *   的分界线；同时报告不进意图路由——寒暄永远不会被误分到"生成报告"。
 *
 * GET 而非 POST：生成无副作用（只读 + 一次叙事 LLM 调用），语义上是取一份报告。
 * 契约：GET /api/assessment/{sessionId}/report?candidateId=xxx
 *   返回结构化报告 JSON（覆盖矩阵/未覆盖考点/计划外考点/跨会话薄弱点/结论建议），
 *   字段结构见 AssessmentReportService.generate。
 */
@RestController
@RequestMapping("/api/assessment")
public class AssessmentController {

    private final AssessmentReportService assessmentReportService;

    public AssessmentController(AssessmentReportService assessmentReportService) {
        this.assessmentReportService = assessmentReportService;
    }

    @GetMapping("/{sessionId}/report")
    public Map<String, Object> report(@PathVariable String sessionId,
                                      @RequestParam(required = false) String candidateId) {
        // candidateId 归一化：未传/空串 → 默认候选人（与 /chat 同口径，跨会话薄弱点段落可用）
        String cid = (candidateId == null || candidateId.isBlank())
                ? SessionKeys.DEFAULT_CANDIDATE_ID
                : candidateId;
        return assessmentReportService.generate(sessionId, cid);
    }

}
