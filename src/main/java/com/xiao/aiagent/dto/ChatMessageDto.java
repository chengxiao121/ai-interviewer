package com.xiao.aiagent.dto;

/**
 * 历史消息 DTO：不直接返回 Message，避免序列化出大量 metadata 噪音。
 * （原内联在 MemoryController 中，P3 重构抽出）
 */
public record ChatMessageDto(String role, String content) {
}
