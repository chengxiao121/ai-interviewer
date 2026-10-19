package com.xiao.aiagent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 全局异常处理（审查报告 三、2「全局异常处理缺失」整改）。
 *
 * 为什么收口在 HTTP 面：全项目 Service/Agent 层抛出的异常类型本就只有四类
 * （IllegalArgumentException / IllegalStateException / RuntimeException / ResponseStatusException），
 * 在 Controller 层做"分类拦截 + 兜底"即可全覆盖，业务代码零侵入。
 * 匹配机制：Spring 从实际异常类型沿继承链向上找最匹配的 handler，
 * 找不到的任何异常最终落进 Exception.class 兜底 —— 先有兜底、再按需细分。
 *
 * 覆盖边界（不属于本类职责）：
 *   - SSE 流建立后的错误：HTTP 已 200，拦不到，需在 Flux 链上 onErrorMap 转业务前缀帧（第二步方案）；
 *   - 404 路由级错误：不进 Controller 方法，仍走 Boot 默认处理；
 *   - 非 Web 线程（@Async/@Scheduled）：将来引入时需另行 try-catch。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 统一错误体：{code, message, traceId}——code 与 HTTP 状态一致，前端按 code 分类提示 */
    public record ApiError(int code, String message, String traceId) {
    }

    /**
     * 业务校验类异常 → 400，中文 message 原样透传给前端。
     * 覆盖：MaterialStoreService（7 处）、MaterialInterviewPipeline 的 IllegalArgumentException。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 参数校验异常 → 400。
     * 本项目是 WebFlux 栈，校验异常类型是 WebExchangeBindException
     * （MVC 的 MethodArgumentNotValidException 不生效）。将来给 DTO 加 @Valid 即自动走这里。
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiError> handleValidation(WebExchangeBindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数不合法");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 显式透传 ResponseStatusException，按其自身状态码与 reason 返回。
     *
     * 为什么必须单独处理：AssistantController 的所有 400 中文提示（"请先完成面试入场"等）
     * 依赖 ResponseStatusException 生效；若不写这个 handler，兜底的 Exception handler
     * 会把它们拦成 500，等于改坏现有行为。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException e) {
        return build(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason());
    }

    /**
     * 未知异常 → 500：message 用固定文案，不透内部细节（含 IllegalStateException/RuntimeException 等）；
     * 完整堆栈进日志并带 traceId，排障时前端报错附带 traceId 即可定位日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception e) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] 未捕获异常", traceId, e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误（traceId: " + traceId + "）", traceId);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return build(status, message, null);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, String traceId) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(),
                        message == null ? status.getReasonPhrase() : message,
                        traceId));
    }
}
