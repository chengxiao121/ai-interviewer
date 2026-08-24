package com.xiao.aiagent.controller;

import com.xiao.aiagent.services.CodeReviewPipeline;
import com.xiao.aiagent.services.InterviewAssistant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RequestMapping("/api/assistant")
@RestController
public class AssistantController {

    private final InterviewAssistant agent;
    // 阶段 4.1 新增：双 Agent 流水线（代码分析 → 面试官），代码评审面试意图走它
    private final CodeReviewPipeline codeReviewPipeline;

    public AssistantController(InterviewAssistant agent, CodeReviewPipeline codeReviewPipeline) {
        this.agent = agent;
        this.codeReviewPipeline = codeReviewPipeline;
    }

    /**
     * 面试对话（SSE 流式）——多 Agent 系统的总入口（阶段 4.1）。
     *
     * 用 POST + JSON body 传 {userMessage, sessionId}，避免长回答撞 URL 长度上限。
     * 返回 text/event-stream，前端用 fetch + ReadableStream 解析（EventSource 不支持 POST）。
     *
     * 入口路由（4.1 简单规则版，4.2 升级为 LlmRoutingAgent）：
     *   - 「代码评审面试」意图（消息带文件路径/代码关键词）→ 双 Agent 流水线；
     *   - 其他（普通出题/追问/统计）→ 单 Agent 面试官。
     * Controller 只做分发：意图判断逻辑在 CodeReviewPipeline.isCodeReviewRequest()，
     * 4.2 换 LLM 路由时本类不用改。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        // 注意：这里【不要】把分片里的 \n 手动转义成 "\ndata:"！
        // Spring 的 ServerSentEventHttpMessageWriter#writeStringData 对 data 字段本身就会
        // 把 \n 转成 "\ndata:" 续行（反编译已验证）。前端 sse.ts 按 SSE 规范把同帧多条
        // data: 行用 \n 拼接即可原样还原换行。
        // 若在这里再手动转义一次 = 双重转义，流里会混入多余的 "data:" 文字，
        // 前端就会在页面里显示出一堆 data: 前缀。
        if (CodeReviewPipeline.isCodeReviewRequest(request.userMessage())) {
            // 代码评审面试 → 双 Agent 流水线（分析 Agent 读代码 → 面试官 Agent 出题）
            return codeReviewPipeline.codeReviewChat(request.userMessage(), request.sessionId());
        }
        // 普通面试 → 单 Agent 面试官
        return agent.chat(request.userMessage(), request.sessionId());
    }

    /** chat 请求体 */
    public record ChatRequest(String userMessage, String sessionId) {
    }

}
