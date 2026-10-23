package com.xiao.aiagent.dto;

/**
 * 统一错误体：{code, message, traceId}——code 与 HTTP 状态一致，前端按 code 分类提示。
 * （原内联在 GlobalExceptionHandler 中，P3 重构抽出）
 */
public record ApiError(int code, String message, String traceId) {
}
