package com.xiao.aiagent.controller;

import com.xiao.aiagent.services.AgentRouter;
import com.xiao.aiagent.tools.SessionKeys;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RequestMapping("/api/assistant")
@RestController
public class AssistantController {

    // 阶段 4.2：统一入口换成意图路由 Agent（LlmRoutingAgent）。Controller 只做"收请求 → 转交 → 吐流"。
    private final AgentRouter agentRouter;

    public AssistantController(AgentRouter agentRouter) {
        this.agentRouter = agentRouter;
    }

    /**
     * 面试对话（SSE 流式）——多 Agent 系统的总入口（阶段 4.2）。
     *
     * 用 POST + JSON body 传 {userMessage, sessionId}，避免长回答撞 URL 长度上限。
     * 返回 text/event-stream，前端用 fetch + ReadableStream 解析（EventSource 不支持 POST）。
     *
     * 路由演进：
     *   - 4.1：Controller 里 if/else 规则分发（isCodeReviewRequest 正则/关键词）；
     *   - 4.2：全部交给 AgentRouter（LlmRoutingAgent 意图路由）——
     *     「代码评审面试」→ 4.1 双 Agent 流水线 /「直接出题面试」→ 面试官 /「闲聊」→ 闲聊 Agent，
     *     fallback 兜底面试官。Controller 彻底变薄：只做 HTTP 收发包，路由逻辑在 AgentRouter。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        // 注意：这里【不要】把分片里的 \n 手动转义成 "\ndata:"！
        // Spring 的 ServerSentEventHttpMessageWriter#writeStringData 对 data 字段本身就会
        // 把 \n 转成 "\ndata:" 续行。前端 sse.ts 按 SSE 规范把同帧多条 data: 行用 \n 拼接即可还原换行。
        // 若在这里再手动转义一次 = 双重转义，流里会混入多余的 "data:" 文字。
        // candidateId 归一化（4.3）：未传/空串 → 默认候选人 "default"（轻量方案：固定值即跨会话聚合可用）
        String candidateId = (request.candidateId() == null || request.candidateId().isBlank())
                ? SessionKeys.DEFAULT_CANDIDATE_ID
                : request.candidateId();
        // 阶段 7：带上传资料 → 直通资料流水线（类型/文本已在上传时锁定，跳过三道关口）；
        // 不带 → 走既有三道关口路由。两路互斥覆盖全部入口。
        if (request.materialIds() != null && !request.materialIds().isEmpty()) {
            return agentRouter.chatWithMaterials(
                    request.userMessage(), request.materialIds(), request.sessionId(), candidateId);
        }
        return agentRouter.chat(request.userMessage(), request.sessionId(), candidateId);
    }

    /** chat 请求体（materialIds 可选：上传资料 id 列表，来自 /api/materials/upload） */
    public record ChatRequest(String userMessage, String sessionId, String candidateId,
                              List<String> materialIds) {
    }

}
