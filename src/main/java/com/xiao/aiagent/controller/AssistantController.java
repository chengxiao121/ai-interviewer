package com.xiao.aiagent.controller;

import com.xiao.aiagent.services.InterviewAssistant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RequestMapping("/api/assistant")
@RestController
public class AssistantController {

    private final InterviewAssistant agent;

    public AssistantController(InterviewAssistant agent) {
        this.agent = agent;
    }

    @GetMapping("/chat")
    public Flux<String> chat(@RequestParam(name = "userMessage") String userMessage,
                             @RequestParam(defaultValue = "default") String sessionId){
        // ChatClient.call() 是阻塞调用，WebFlux 中需放到 boundedElastic 线程池执行，避免阻塞 Netty 事件循环线程
        return agent.chat(userMessage, sessionId);
    }

}
