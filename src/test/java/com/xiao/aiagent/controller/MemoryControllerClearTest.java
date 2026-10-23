package com.xiao.aiagent.controller;
import com.xiao.aiagent.dto.ClearResult;

import com.xiao.aiagent.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * MemoryController.clear 失败可见性单测（事务与并发安全修复的回归锁定）。
 *
 * clear 跨两个存储（Redis 记忆 + PG 入场绑定），无跨存储事务。修复前无论成败恒返回 cleared=true
 * （伪级联 + 掩盖失败）；修复后每步失败各自暴露在返回值与 error 日志里——本测试锁定该语义不回退。
 */
@DisplayName("MemoryController.clear：跨存储清空的失败可见性")
class MemoryControllerClearTest {

    private static final String SESSION_ID = "s1";

    private ChatMemory chatMemory;
    private ChatMemoryRepository chatMemoryRepository;
    private InterviewSessionRepository interviewSessionRepository;
    private MemoryController controller;

    @BeforeEach
    void setUp() {
        chatMemory = Mockito.mock(ChatMemory.class);
        chatMemoryRepository = Mockito.mock(ChatMemoryRepository.class);
        interviewSessionRepository = Mockito.mock(InterviewSessionRepository.class);
        controller = new MemoryController(chatMemory, chatMemoryRepository, interviewSessionRepository);
    }

    @Nested
    @DisplayName("正常路径")
    class 正常路径 {

        @Test
        @DisplayName("两步都成功 → cleared=true 且两步结果各自为 true")
        void 两步都成功() {
            ClearResult result = controller.clear(SESSION_ID);

            assertTrue(result.cleared());
            assertTrue(result.memoryCleared());
            assertTrue(result.bindingCleared());
            verify(interviewSessionRepository).deleteBySessionId(SESSION_ID);
        }
    }

    @Nested
    @DisplayName("部分失败（失败不再谎报成功）")
    class 部分失败 {

        @Test
        @DisplayName("绑定删除失败 → cleared=false、bindingCleared=false，但记忆已清成功")
        void 绑定删除失败如实上报() {
            doThrow(new RuntimeException("pg down")).when(interviewSessionRepository).deleteBySessionId(SESSION_ID);

            ClearResult result = controller.clear(SESSION_ID);

            assertFalse(result.cleared());
            assertTrue(result.memoryCleared());
            assertFalse(result.bindingCleared());
        }

        @Test
        @DisplayName("记忆清空失败 → cleared=false、memoryCleared=false，但仍尝试删绑定")
        void 记忆清空失败仍尝试删绑定() {
            doThrow(new RuntimeException("redis down")).when(chatMemory).clear(SESSION_ID);

            ClearResult result = controller.clear(SESSION_ID);

            assertFalse(result.cleared());
            assertFalse(result.memoryCleared());
            assertTrue(result.bindingCleared());
            verify(interviewSessionRepository).deleteBySessionId(SESSION_ID);
        }
    }
}
