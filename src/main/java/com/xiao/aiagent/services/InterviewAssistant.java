package com.xiao.aiagent.services;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class InterviewAssistant {

    private final ChatClient chatClient;
    private final MessageChatMemoryAdvisor chatMemoryAdvisor;

    public InterviewAssistant(ChatClient.Builder builder,
                              MessageChatMemoryAdvisor chatMemoryAdvisor,
                              QuestionAnswerAdvisor questionAnswerAdvisor){
        this.chatClient = builder
                .defaultSystem("""
                        你是一位资深技术面试官，负责对求职者进行技术面试模拟。
                        你的职责：
                        1. 根据岗位和求职者水平出题（Java/Redis/数据库等），题目循序渐进；
                        2. 针对回答进行追问，答错时给予提示引导；
                        3. 点评回答并给出评分与改进建议；
                        4. 出题时优先参考知识库中的题库内容，引用时注明出处。
                        要求：语气专业、友好，全程使用中文。
                        """)
                .defaultAdvisors(questionAnswerAdvisor, chatMemoryAdvisor)         //// RAG 在前，记忆在后
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
