package com.xiao.aiagent.agent;

import com.xiao.aiagent.common.OutputGuard;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.service.AnswerEvaluatorService;
import com.xiao.aiagent.service.InterviewLifecycleService;
import com.xiao.aiagent.service.InterviewPlanService;
import com.xiao.aiagent.service.WeaknessProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StreamingPipelineSupport.buildChatMessages 的状态/模式分叉单测
 * （阶段 11 状态机回归锁定 + 阶段 12 模式分叉）。
 *
 * 基类是抽象类且装配逻辑是 protected——用匿名子类实例化（依赖全 mock）直测，锁定四分支：
 * ① 已结束 → 注入 FINISHED_PROMPT，跳过计划/卷子/评估（不出题不评分），两种模式一致；
 * ② 进行中 + 知识库 → 注入【本场卷子】（renderPaper，出题进度=评分条数+1）+ 模式纪律，
 *    绝不调 ensurePlan（知识库面试靠卷子，不靠 LLM 现生成计划）；
 * ③ 进行中 + 资料模式 → 计划照常（原行为不变）；
 * ④ 未入场的会话（无绑定）→ 按资料模式处理（老行为兼容）。
 * 题库 RAG 已在阶段 7 整体删除（知识库面试靠入场组卷），装配链路不再有任何向量检索依赖。
 */
@DisplayName("buildChatMessages：状态/模式分叉（结束提示 / 卷子注入 / 计划注入）")
class StreamingPipelineBuildChatMessagesTest {

    private static final String SESSION_ID = "s1";
    private static final String CANDIDATE_ID = "c1";

    private ChatMemory chatMemory;
    private WeaknessProfileService weaknessProfileService;
    private InterviewPlanService interviewPlanService;
    private AnswerEvaluatorService answerEvaluatorService;
    private InterviewLifecycleService lifecycleService;
    private StreamingPipelineSupport support;

    /** 匿名子类：基类抽象但无其他抽象方法，最小实现即可直测装配逻辑 */
    private static StreamingPipelineSupport newSupport(ChatMemory chatMemory,
                                                       WeaknessProfileService weakness,
                                                       InterviewPlanService planService,
                                                       AnswerEvaluatorService evaluator,
                                                       InterviewLifecycleService lifecycle) {
        return new StreamingPipelineSupport(
                chatMemory, null, weakness, planService, evaluator,
                lifecycle, OutputGuard.Mode.OFF) {
        };
    }

    /** 造一条指定状态与模式的会话绑定 */
    private static InterviewSession session(String status, String mode) {
        InterviewSession s = new InterviewSession(SESSION_ID, CANDIDATE_ID, List.of());
        s.setStatus(status);
        s.setMode(mode);
        return s;
    }

    @BeforeEach
    void setUp() {
        chatMemory = Mockito.mock(ChatMemory.class);
        weaknessProfileService = Mockito.mock(WeaknessProfileService.class);
        interviewPlanService = Mockito.mock(InterviewPlanService.class);
        answerEvaluatorService = Mockito.mock(AnswerEvaluatorService.class);
        lifecycleService = Mockito.mock(InterviewLifecycleService.class);
        support = newSupport(chatMemory, weaknessProfileService,
                interviewPlanService, answerEvaluatorService, lifecycleService);

        // 公共桩：薄弱点为空（不注入）、评估注入为空（不干扰断言）、记忆窗口为空。
        when(weaknessProfileService.loadProfile(anyString())).thenReturn("");
        when(answerEvaluatorService.evaluateTurn(anyString(), anyString(), anyString(), any()))
                .thenReturn(new AnswerEvaluatorService.EvaluationOutcome(false, ""));
        when(answerEvaluatorService.countScored(anyString())).thenReturn(0);
        when(chatMemory.get(anyString())).thenReturn(List.of());
        // 渲染类默认桩：真实实现契约是"降级返回空串、绝不返回 null"（mock 默认返回 null 会伪装出 NPE）；
        // 具体用例需要特定渲染结果时在用例内覆盖
        when(interviewPlanService.renderPaper(any(), anyInt())).thenReturn("");
        when(interviewPlanService.renderPlan(any())).thenReturn("");
    }

    /** 统计装配结果里的 SystemMessage 正文 */
    private String systemTexts(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (m instanceof SystemMessage sm) {
                sb.append(sm.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("已结束的会话（两种模式行为一致）")
    class 已结束 {

        @Test
        @DisplayName("资料模式：注入不出题提示；跳过计划与评估；不调用题库 RAG")
        void 资料模式已结束() {
            when(lifecycleService.loadSession(SESSION_ID))
                    .thenReturn(Optional.of(session(InterviewSession.STATUS_FINISHED, InterviewSession.MODE_MATERIAL)));

            List<Message> messages = support.buildChatMessages("什么是线程池？", SESSION_ID, CANDIDATE_ID);
            String system = systemTexts(messages);

            assertTrue(system.contains("本场面试已经结束"), "应注入 FINISHED_PROMPT");
            verify(interviewPlanService, never()).ensurePlan(anyString(), anyString(), anyString());
            verify(answerEvaluatorService, never())
                    .evaluateTurn(anyString(), anyString(), anyString(), any());
            assertTrue(messages.get(messages.size() - 1) instanceof UserMessage, "本轮消息仍在末位");
        }

        @Test
        @DisplayName("知识库模式：同样只注入不出题提示，不再注入卷子与评估")
        void 知识库模式已结束() {
            when(lifecycleService.loadSession(SESSION_ID))
                    .thenReturn(Optional.of(session(InterviewSession.STATUS_FINISHED, InterviewSession.MODE_KNOWLEDGE)));

            String system = systemTexts(support.buildChatMessages("总结一下", SESSION_ID, CANDIDATE_ID));

            assertTrue(system.contains("本场面试已经结束"));
            assertFalse(system.contains("【本场卷子】"), "已结束不再注入卷子");
            verify(interviewPlanService, never()).loadPlan(anyString());
            verify(answerEvaluatorService, never())
                    .evaluateTurn(anyString(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("进行中的会话")
    class 进行中 {

        @Test
        @DisplayName("资料模式：不注入结束提示；计划与评估照常（原行为不变）；不调 RAG")
        void 资料模式原行为不变() {
            when(lifecycleService.loadSession(SESSION_ID))
                    .thenReturn(Optional.of(session(InterviewSession.STATUS_IN_PROGRESS, InterviewSession.MODE_MATERIAL)));
            InterviewPlan plan = new InterviewPlan();
            when(interviewPlanService.ensurePlan(anyString(), anyString(), anyString())).thenReturn(plan);
            when(interviewPlanService.renderPlan(plan)).thenReturn("【面试计划】Java 并发");

            String system = systemTexts(support.buildChatMessages("线程池核心参数有哪些", SESSION_ID, CANDIDATE_ID));

            assertFalse(system.contains("本场面试已经结束"));
            assertTrue(system.contains("【面试计划】"), "进行中的会话计划照常注入");
            verify(answerEvaluatorService).evaluateTurn(anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("未入场的会话（无绑定）→ 按资料模式处理（老行为兼容）")
        void 未入场按资料模式() {
            when(lifecycleService.loadSession(SESSION_ID)).thenReturn(Optional.empty());
            InterviewPlan plan = new InterviewPlan();
            when(interviewPlanService.ensurePlan(anyString(), anyString(), anyString())).thenReturn(plan);
            when(interviewPlanService.renderPlan(plan)).thenReturn("【面试计划】自由面");

            support.buildChatMessages("来一场面试", SESSION_ID, CANDIDATE_ID);

            verify(interviewPlanService).ensurePlan(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("知识库模式：注入卷子（出题进度=评分条数+1）与模式纪律；绝不调 ensurePlan")
        void 知识库模式注入卷子() {
            when(lifecycleService.loadSession(SESSION_ID))
                    .thenReturn(Optional.of(session(InterviewSession.STATUS_IN_PROGRESS, InterviewSession.MODE_KNOWLEDGE)));
            InterviewPlan plan = new InterviewPlan();
            when(interviewPlanService.loadPlan(SESSION_ID)).thenReturn(Optional.of(plan));
            when(interviewPlanService.renderPaper(plan, 4)).thenReturn("【本场卷子】第 4 题……");
            when(answerEvaluatorService.countScored(SESSION_ID)).thenReturn(3); // 已评 3 道 → 本轮应出第 4 题

            String system = systemTexts(support.buildChatMessages("下一题", SESSION_ID, CANDIDATE_ID));

            assertTrue(system.contains("【本场卷子】"), "知识库模式注入卷子");
            assertTrue(system.contains("【本场模式】"), "注入模式纪律覆盖（不追问）");
            assertFalse(system.contains("【面试计划】"));
            verify(interviewPlanService).renderPaper(plan, 4);
            verify(interviewPlanService, never()).ensurePlan(anyString(), anyString(), anyString());
            verify(answerEvaluatorService).evaluateTurn(anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("知识库模式查不到卷子（数据异常）→ 不炸：无卷子注入但模式纪律仍在")
        void 知识库无卷子降级() {
            when(lifecycleService.loadSession(SESSION_ID))
                    .thenReturn(Optional.of(session(InterviewSession.STATUS_IN_PROGRESS, InterviewSession.MODE_KNOWLEDGE)));
            when(interviewPlanService.loadPlan(SESSION_ID)).thenReturn(Optional.empty());

            String system = systemTexts(support.buildChatMessages("开始吧", SESSION_ID, CANDIDATE_ID));

            assertFalse(system.contains("【本场卷子】"));
            assertTrue(system.contains("【本场模式】"), "纪律提示照常注入");
            verify(interviewPlanService, never()).ensurePlan(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("首轮（评分 0 条）→ 出题进度为第 1 题")
        void 首轮进度() {
            when(lifecycleService.loadSession(SESSION_ID))
                    .thenReturn(Optional.of(session(InterviewSession.STATUS_IN_PROGRESS, InterviewSession.MODE_KNOWLEDGE)));
            InterviewPlan plan = new InterviewPlan();
            when(interviewPlanService.loadPlan(SESSION_ID)).thenReturn(Optional.of(plan));
            when(interviewPlanService.renderPaper(eq(plan), eq(1))).thenReturn("【本场卷子】第 1 题……");

            support.buildChatMessages("开始", SESSION_ID, CANDIDATE_ID);

            verify(interviewPlanService).renderPaper(plan, 1);
        }
    }
}
