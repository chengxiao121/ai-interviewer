package com.xiao.aiagent.controller;

import com.xiao.aiagent.repository.InterviewSessionRepository;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant/memory")
public class MemoryController {

    // 记忆策略层：查历史、清空会话
    private final ChatMemory chatMemory;
    // 记忆存储层：只有它暴露"列出所有会话"的能力
    private final ChatMemoryRepository chatMemoryRepository;
    // 面试入场绑定（阶段 8）：清空会话时级联删除，避免留下孤儿绑定
    private final InterviewSessionRepository interviewSessionRepository;

    public MemoryController(ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository,
                            InterviewSessionRepository interviewSessionRepository) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
        this.interviewSessionRepository = interviewSessionRepository;
    }

    // 查历史：返回该会话滑动窗口内的所有消息（最多 20 条）
    @GetMapping("/{sessionId}")
    public List<ChatMessageDto> history(@PathVariable String sessionId) {
        List<Message> messages = chatMemory.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .map(m -> new ChatMessageDto(m.getMessageType().name(), m.getText()))
                .toList();
    }

    // 清空会话记忆：删除 Redis 中该会话的 key，之后同 sessionId 再聊是全新会话；
    // 级联删除入场绑定（阶段 8）——会话没了，"谁在面、用什么资料"的绑定也不该留着
    @DeleteMapping("/{sessionId}")
    public ClearResult clear(@PathVariable String sessionId) {
        chatMemory.clear(sessionId);
        interviewSessionRepository.deleteBySessionId(sessionId);
        return new ClearResult(sessionId, true);
    }

    // 列出所有会话 id（能力在存储层，ChatMemory 接口没有此方法）
    @GetMapping
    public List<String> listConversations() {
        return chatMemoryRepository.findConversationIds();
    }

    // 历史消息 DTO：不直接返回 Message，避免序列化出大量 metadata 噪音
    public record ChatMessageDto(String role, String content) {
    }

    // 清空会话结果
    public record ClearResult(String sessionId, boolean cleared) {
    }
}