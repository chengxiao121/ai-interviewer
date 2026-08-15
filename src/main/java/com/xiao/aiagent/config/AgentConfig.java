package com.xiao.aiagent.config;

import com.alibaba.cloud.ai.memory.redis.JedisRedisChatMemoryRepository;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    // 记忆存储：Redis 持久化（memory-redis starter 不带自动装配，手动创建 Bean）
    // 连接参数复用 spring.data.redis.* 配置，不用再写死
    @Bean
    public ChatMemoryRepository chatMemoryRepository(RedisProperties redisProperties) {
        return JedisRedisChatMemoryRepository.builder()
                .host(redisProperties.getHost())
                .port(redisProperties.getPort())
                .database(redisProperties.getDatabase())
                .build();
    }

    // 短期记忆：滑动窗口（最多保留 20 条消息）+ Redis 持久化
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    // 记忆顾问：自动把历史消息注入每次请求（会话由 sessionId 隔离）
    @Bean
    public MessageChatMemoryAdvisor chatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    // RAG 顾问：VectorStore 由 spring-ai-starter-vector-store-redis 自动装配（Jedis 客户端）
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.7)
                        .topK(3)
                        .build())
                .build();
    }

}
