package com.xiao.aiagent.services;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class CustomerSupportAssistant {

    private final ChatClient chatClient;

    public CustomerSupportAssistant(ChatClient.Builder builder){
        this.chatClient = builder
                .defaultSystem("您是航空公司的客户聊天支持代理，请以友好、乐于助人且愉快的方式来回复。请讲中文。")
                .build();
    }

    public Flux<String> chat(String userMessage){
        return this.chatClient.prompt().user(userMessage).stream().content();
    }

}
