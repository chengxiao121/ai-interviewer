package com.xiao.aiagent.controller;

import com.xiao.aiagent.agent.AgentRouter;
import com.xiao.aiagent.dto.ChatRequest;
import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import com.xiao.aiagent.service.MaterialStoreService;
import com.xiao.aiagent.service.PaperComposeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssistantController 双模式入场分叉单测（阶段 12，实施计划完成标准的 400 分支锁定）。
 *
 * 锁定四类分支：
 * ① KNOWLEDGE 入场：缺 kbId / 题库不存在 / 空库 / 容量不足 → 400 中文提示（经 Service 归口），
 *    且【先组卷后落绑定】——组卷失败不留"有绑定无卷子"的半截会话；
 * ② 模式一致性：已绑定会话带不符 mode → 400 显式拒绝（不是 500、不是静默沿用）；
 * ③ 模式归一：mode 缺省 = MATERIAL（旧前端兼容）、大小写不敏感、非法值 400；
 * ④ 路由分叉：KNOWLEDGE → chatForKnowledge（跳过资料读取与三道关口）；MATERIAL → 原路径不变。
 */
@DisplayName("AssistantController：双模式入场分叉")
class AssistantControllerModeBranchTest {

    private static final String SESSION_ID = "s1";

    private AgentRouter agentRouter;
    private MaterialStoreService materialStoreService;
    private InterviewSessionRepository sessionRepository;
    private PaperComposeService paperComposeService;
    private AssistantController controller;

    @BeforeEach
    void setUp() {
        agentRouter = Mockito.mock(AgentRouter.class);
        materialStoreService = Mockito.mock(MaterialStoreService.class);
        sessionRepository = Mockito.mock(InterviewSessionRepository.class);
        paperComposeService = Mockito.mock(PaperComposeService.class);
        controller = new AssistantController(agentRouter, materialStoreService,
                sessionRepository, paperComposeService);

        when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(java.util.Optional.empty());
        when(sessionRepository.save(any(InterviewSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentRouter.chatForKnowledge(anyString(), anyString(), anyString())).thenReturn(Flux.just("ok"));
        when(agentRouter.chatWithMaterials(anyString(), anyList(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));
    }

    /** ResponseStatusException 断言：400 + reason */
    private static ResponseStatusException badRequestOf(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(400, e.getStatusCode().value());
        return e;
    }

    @Nested
    @DisplayName("知识库模式入场（新会话）")
    class 知识库入场 {

        @Test
        @DisplayName("缺 kbId → 400 中文提示，不组卷不落绑定")
        void 缺kbId() {
            ChatRequest request = new ChatRequest("开始", SESSION_ID, "张三", null, "KNOWLEDGE", null, 20);

            ResponseStatusException e = badRequestOf(() -> controller.chat(request));

            assertTrue(e.getReason().contains("知识库"));
            verify(paperComposeService, never()).compose(anyLong(), any(), anyString(), anyString());
            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("题库不存在/空库/容量不足（compose 抛 IAE）→ 异常上抛归口 400，绑定不落库")
        void 组卷失败不落绑定() {
            when(paperComposeService.compose(9L, 20, SESSION_ID, "张三"))
                    .thenThrow(new IllegalArgumentException("该题库共 0 道可用题，最多可选 0 道，无法组 20 道题的卷子，请减少题数或补充题库"));
            ChatRequest request = new ChatRequest("开始", SESSION_ID, "张三", null, "KNOWLEDGE", 9L, 20);

            assertThrows(IllegalArgumentException.class, () -> controller.chat(request));

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常入场 → 先组卷后落绑定；绑定 mode=KNOWLEDGE/kbId/无资料；直连面试官")
        void 正常入场() throws Exception {
            ChatRequest request = new ChatRequest("开始", SESSION_ID, "张三", null, "KNOWLEDGE", 9L, 20);

            controller.chat(request);

            var inOrder = Mockito.inOrder(paperComposeService, sessionRepository);
            inOrder.verify(paperComposeService).compose(9L, 20, SESSION_ID, "张三");
            ArgumentCaptor<InterviewSession> captor = ArgumentCaptor.forClass(InterviewSession.class);
            inOrder.verify(sessionRepository).save(captor.capture());
            InterviewSession saved = captor.getValue();
            assertEquals(InterviewSession.MODE_KNOWLEDGE, saved.getMode());
            assertEquals(9L, saved.getKbId());
            assertTrue(saved.materialIds().isEmpty(), "知识库模式不绑定资料");

            verify(agentRouter).chatForKnowledge("开始", SESSION_ID, "张三");
            verify(agentRouter, never()).chatWithMaterials(anyString(), anyList(), anyString(), anyString());
            verify(materialStoreService, never()).loadAsTexts(any()); // 无资料可读，跳过资料校验
        }

        @Test
        @DisplayName("题数缺省 → compose 收到 null（服务端按默认 20 组卷）")
        void 题数缺省透传() {
            ChatRequest request = new ChatRequest("开始", SESSION_ID, "张三", null, "KNOWLEDGE", 9L, null);

            controller.chat(request);

            verify(paperComposeService).compose(eq(9L), isNull(), eq(SESSION_ID), eq("张三"));
        }
    }

    @Nested
    @DisplayName("模式一致性（已绑定会话）")
    class 模式一致性 {

        @Test
        @DisplayName("已绑定资料模式，改传 KNOWLEDGE → 400 显式拒绝（不静默沿用）")
        void 资料会话传知识库() {
            when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(java.util.Optional.of(
                    boundSession(InterviewSession.MODE_MATERIAL, List.of("RESUME-abc"))));
            ChatRequest request = new ChatRequest("换个方式面", SESSION_ID, null, List.of("RESUME-abc"), "KNOWLEDGE", 9L, null);

            ResponseStatusException e = badRequestOf(() -> controller.chat(request));

            assertTrue(e.getReason().contains("已绑定"));
            verify(paperComposeService, never()).compose(anyLong(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("已绑定知识库模式，改传 MATERIAL → 400 显式拒绝")
        void 知识库会话传资料() {
            when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(java.util.Optional.of(
                    boundSession(InterviewSession.MODE_KNOWLEDGE, List.of())));
            ChatRequest request = new ChatRequest("继续", SESSION_ID, null, null, "MATERIAL", null, null);

            ResponseStatusException e = badRequestOf(() -> controller.chat(request));

            assertTrue(e.getReason().contains("已绑定"));
        }

        @Test
        @DisplayName("后续轮次不带 mode → 沿用绑定正常对话（兼容）")
        void 不带mode沿用绑定() {
            when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(java.util.Optional.of(
                    boundSession(InterviewSession.MODE_KNOWLEDGE, List.of())));

            controller.chat(new ChatRequest("继续", SESSION_ID, null, null, null, null, null));

            verify(agentRouter).chatForKnowledge("继续", SESSION_ID, "李四");
        }
    }

    @Nested
    @DisplayName("模式归一与资料模式回归")
    class 模式归一与资料回归 {

        @Test
        @DisplayName("mode 大小写不敏感；非法值 → 400 中文提示")
        void 归一规则() {
            assertEquals(InterviewSession.MODE_MATERIAL, AssistantController.normalizeMode(null));
            assertEquals(InterviewSession.MODE_MATERIAL, AssistantController.normalizeMode("  "));
            assertEquals(InterviewSession.MODE_KNOWLEDGE, AssistantController.normalizeMode("knowledge"));

            ResponseStatusException e = badRequestOf(() -> AssistantController.normalizeMode("quiz"));
            assertTrue(e.getReason().contains("面试模式仅支持"));
        }

        @Test
        @DisplayName("缺省 mode = 资料模式：缺资料 → 400（原入场引导不变）")
        void 缺省走资料校验() {
            ChatRequest request = new ChatRequest("开始", SESSION_ID, "张三", null, null, null, null);

            ResponseStatusException e = badRequestOf(() -> controller.chat(request));

            assertTrue(e.getReason().contains("请先完成面试入场"));
            verify(agentRouter, never()).chatForKnowledge(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("资料模式正常入场 → 行为与阶段 12 前一致（资料校验 + 资料流水线路径）")
        void 资料模式回归() throws Exception {
            ChatRequest request = new ChatRequest("开始", SESSION_ID, "张三",
                    List.of("RESUME-abc"), null, null, null);
            when(materialStoreService.loadAsTexts(List.of("RESUME-abc"))).thenReturn(java.util.Map.of());

            controller.chat(request);

            verify(agentRouter).chatWithMaterials("开始", List.of("RESUME-abc"), SESSION_ID, "张三");
            verify(agentRouter, never()).chatForKnowledge(anyString(), anyString(), anyString());
            verify(paperComposeService, never()).compose(anyLong(), any(), anyString(), anyString());
        }
    }

    private static InterviewSession boundSession(String mode, List<String> materialIds) {
        InterviewSession s = new InterviewSession(SESSION_ID, "李四", materialIds);
        s.setMode(mode);
        if (InterviewSession.MODE_KNOWLEDGE.equals(mode)) {
            s.setKbId(9L);
        }
        return s;
    }
}
