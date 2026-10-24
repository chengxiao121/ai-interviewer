package com.xiao.aiagent.agent;

import com.xiao.aiagent.common.OutputGuard;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.service.AnswerEvaluatorService;
import com.xiao.aiagent.service.InterviewLifecycleService;
import com.xiao.aiagent.service.InterviewPlanService;
import com.xiao.aiagent.service.KnowledgeSearchService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StreamingPipelineSupport.buildChatMessages 的会话状态分叉单测（阶段 11 回归锁定）。
 *
 * 基类是抽象类且装配逻辑是 protected——用匿名子类实例化（依赖全 mock）直测，
 * 锁定两分支：
 * ① 已结束 → 注入 FINISHED_PROMPT，跳过 ensurePlan/evaluateTurn（不出题不评分），RAG 照常；
 * ② 进行中 → 无 FINISHED_PROMPT，计划与评估照常注入（原行为不变）。
 */
@DisplayName("buildChatMessages：已结束面试注入不出题提示并跳过计划/评估")
class StreamingPipelineBuildChatMessagesTest {

    private static final String SESSION_ID = "s1";
    private static final String CANDIDATE_ID = "c1";

    private ChatMemory chatMemory;
    private KnowledgeSearchService knowledgeSearchService;
    private WeaknessProfileService weaknessProfileService;
    private InterviewPlanService interviewPlanService;
    private AnswerEvaluatorService answerEvaluatorService;
    private InterviewLifecycleService lifecycleService;
    private StreamingPipelineSupport support;

    /** 匿名子类：基类抽象但无其他抽象方法，最小实现即可直测装配逻辑 */
    private static StreamingPipelineSupport newSupport(ChatMemory chatMemory,
                                                       KnowledgeSearchService rag,
                                                       WeaknessProfileService weakness,
                                                       InterviewPlanService planService,
                                                       AnswerEvaluatorService evaluator,
                                                       InterviewLifecycleService lifecycle) {
        return new StreamingPipelineSupport(
                chatMemory, null, rag, weakness, planService, evaluator,
                lifecycle, OutputGuard.Mode.OFF) {
        };
    }

    @BeforeEach
    void setUp() {
        chatMemory = Mockito.mock(ChatMemory.class);
        knowledgeSearchService = Mockito.mock(KnowledgeSearchService.class);
        weaknessProfileService = Mockito.mock(WeaknessProfileService.class);
        interviewPlanService = Mockito.mock(InterviewPlanService.class);
        answerEvaluatorService = Mockito.mock(AnswerEvaluatorService.class);
        lifecycleService = Mockito.mock(InterviewLifecycleService.class);
        support = newSupport(chatMemory, knowledgeSearchService, weaknessProfileService,
                interviewPlanService, answerEvaluatorService, lifecycleService);

        // 公共桩：薄弱点为空（不注入）、RAG 命中、评估注入为空（不干扰断言）、记忆窗口为空
        when(weaknessProfileService.loadProfile(anyString())).thenReturn("");
        when(knowledgeSearchService.search(anyString())).thenReturn("【题库参考】并发基础知识");
        when(answerEvaluatorService.evaluateTurn(anyString(), anyString(), anyString(), any()))
                .thenReturn(new AnswerEvaluatorService.EvaluationOutcome(false, ""));
        when(chatMemory.get(anyString())).thenReturn(List.of());
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
    @DisplayName("已结束的会话")
    class 已结束 {

        @Test
        @DisplayName("注入不出题提示；跳过计划生成与评估；题库 RAG 照常（答疑要用）")
        void 注入结束提示并跳过出题链路() {
            when(lifecycleService.isFinished(SESSION_ID)).thenReturn(true);

            List<Message> messages = support.buildChatMessages("什么是线程池？", SESSION_ID, CANDIDATE_ID);
            String system = systemTexts(messages);

            assertTrue(system.contains("本场面试已经结束"), "应注入 FINISHED_PROMPT");
            verify(interviewPlanService, never()).ensurePlan(anyString(), anyString(), anyString());
            verify(answerEvaluatorService, never())
                    .evaluateTurn(anyString(), anyString(), anyString(), any());
            verify(knowledgeSearchService).search("什么是线程池？"); // 答疑仍可用题库
            assertTrue(messages.get(messages.size() - 1) instanceof UserMessage, "本轮消息仍在末位");
        }
    }

    @Nested
    @DisplayName("进行中的会话")
    class 进行中 {

        @Test
        @DisplayName("不注入结束提示；计划与评估照常执行（原行为不变）")
        void 原行为不变() {
            when(lifecycleService.isFinished(SESSION_ID)).thenReturn(false);
            InterviewPlan plan = new InterviewPlan();
            when(interviewPlanService.ensurePlan(anyString(), anyString(), anyString())).thenReturn(plan);
            when(interviewPlanService.renderPlan(plan)).thenReturn("【面试计划】Java 并发");

            List<Message> messages = support.buildChatMessages("线程池核心参数有哪些", SESSION_ID, CANDIDATE_ID);
            String system = systemTexts(messages);

            assertFalse(system.contains("本场面试已经结束"));
            assertTrue(system.contains("【面试计划】"), "进行中的会话计划照常注入");
            verify(answerEvaluatorService).evaluateTurn(anyString(), anyString(), anyString(), any());
        }
    }
}
