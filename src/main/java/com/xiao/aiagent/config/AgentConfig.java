package com.xiao.aiagent.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.memory.redis.JedisRedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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

    // 短期记忆：滑动窗口 + Redis 持久化。
    // maxMessages=60：qwen3.7-plus 上下文窗口 1M token，一场面试全部历史远未到上限，
    // 直接调大窗口全量装入，无需摘要压缩（原"会话内摘要压缩"方案已论证砍掉）。
    // 阶段 3 起不再靠 MessageChatMemoryAdvisor 自动注入，改由 InterviewAssistant 手动读写窗口
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(60)
                .build();
    }

    // Agent 运行状态保存器：ReactAgent 的 ReAct 循环 checkpoint（"记录本快照"）按 threadId(=sessionId) 隔离。
    // 用内存版 MemorySaver：RedisSaver 依赖 Redisson 客户端，与向量库强依赖的 Jedis 冲突，不引入；
    // 单次 ReAct 运行结束会 release 掉 checkpoint（见 InterviewAssistant），不会无限累积。
    // 长期会话记忆不靠这里：它由 chatMemory（Redis 窗口）承担，二者分工是"双层记忆"的雏形。
    @Bean
    public MemorySaver memorySaver() {
        return new MemorySaver();
    }

}
