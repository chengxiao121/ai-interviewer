package com.xiao.aiagent.services;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class CustomerSupportAssistant {

    private final ChatClient chatClient;
    private final MessageChatMemoryAdvisor chatMemoryAdvisor;

    public CustomerSupportAssistant(ChatClient.Builder builder,
                                    MessageChatMemoryAdvisor chatMemoryAdvisor){
        this.chatClient = builder
                .defaultSystem("您是航空公司的客户聊天支持代理，请以友好、乐于助人且愉快的方式来回复。请讲中文。")
                .defaultAdvisors(chatMemoryAdvisor)         //// 注册记忆顾问
                .build();
        this.chatMemoryAdvisor = chatMemoryAdvisor;
    }

    public Flux<String> chat(String userMessage, String sessionId){
        return this.chatClient
                .prompt()
                .user(userMessage)
                .advisors(a -> a.param("chat_memory_conversation_id", sessionId))
                .stream()
                .content();
    }

}
