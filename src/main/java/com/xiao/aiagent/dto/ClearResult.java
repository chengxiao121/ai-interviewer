package com.xiao.aiagent.dto;

/**
 * 清空会话结果：cleared = 两步全部成功；memoryCleared / bindingCleared 分别暴露每步结果。
 * （原内联在 MemoryController 中，P3 重构抽出）
 */
public record ClearResult(String sessionId, boolean cleared, boolean memoryCleared, boolean bindingCleared) {
}
