package com.xiao.aiagent.controller;

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

    public AssistantController(InterviewAssistant agent) {
        this.agent = agent;
    }

    /**
     * 面试对话（SSE 流式）。
     * 用 POST + JSON body 传 {userMessage, sessionId}，避免长回答撞 URL 长度上限。
     * 返回 text/event-stream，前端用 fetch + ReadableStream 解析（EventSource 不支持 POST）。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        return agent.chat(request.userMessage(), request.sessionId());
    }

    /** chat 请求体 */
    public record ChatRequest(String userMessage, String sessionId) {
    }

}
