package com.xiao.aiagent.controller;

import com.xiao.aiagent.dto.ChatMessageDto;
import com.xiao.aiagent.dto.ClearResult;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

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

    // 清空会话：跨两个存储（Redis 记忆 + PG 入场绑定），无跨存储事务，失败补偿不划算——
    // 策略是"失败可见"：任一步失败记 error 日志并如实反映在返回值里，不再恒报成功。
    // 注意半成品状态的可能性：记忆清了但绑定删失败 → 残留孤儿绑定（下次入场同 sessionId 会覆盖，影响有限）。
    @DeleteMapping("/{sessionId}")
    public ClearResult clear(@PathVariable String sessionId) {
        boolean memoryCleared = true;
        boolean bindingCleared = true;
        try {
            chatMemory.clear(sessionId);
        } catch (Exception e) {
            memoryCleared = false;
            log.error("清空会话记忆失败：sessionId={}", sessionId, e);
        }
        try {
            interviewSessionRepository.deleteBySessionId(sessionId);
        } catch (Exception e) {
            bindingCleared = false;
            log.error("删除入场绑定失败（级联删除未完成，绑定残留）：sessionId={}", sessionId, e);
        }
        return new ClearResult(sessionId, memoryCleared && bindingCleared, memoryCleared, bindingCleared);
    }

    // 列出所有会话 id（能力在存储层，ChatMemory 接口没有此方法）
    @GetMapping
    public List<String> listConversations() {
        return chatMemoryRepository.findConversationIds();
    }

    // 历史消息 DTO：不直接返回 Message，避免序列化出大量 metadata 噪音（已抽出至 dto.ChatMessageDto）
    // 清空会话结果：cleared = 两步全部成功；memoryCleared / bindingCleared 分别暴露每步结果（已抽出至 dto.ClearResult）
}