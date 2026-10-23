package com.xiao.aiagent.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.repository.MaterialProfileRepository;
import com.xiao.aiagent.repository.InterviewPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * InterviewPlanService.ensurePlan 并发兜底单测（事务与并发安全修复的回归锁定）。
 *
 * ensurePlan 是"一场一份计划"契约的唯一生产方，锁定两个分支：
 * ① 首轮正常生成落库；② 并发首轮（双开标签页）两边都查到"无计划"各生成一份，
 * 后落库者撞唯一约束 uk_plan_session → 必须读回先落库的那份返回（谁先谁赢，出题口径统一）。
 */
@DisplayName("InterviewPlanService.ensurePlan：计划生成幂等与并发兜底")
class InterviewPlanServiceEnsurePlanTest {

    private static final String SESSION_ID = "s1";
    private static final String CANDIDATE_ID = "c1";
    /** 模型输出的考点数组（parseTopics 可解析的最小形态） */
    private static final String TOPICS_JSON =
            "[{\"topic\":\"线程池参数\",\"priority\":\"高\",\"difficulty\":\"进阶\"}]";

    private ChatModel chatModel;
    private MaterialProfileRepository materialProfileRepository;
    private InterviewPlanRepository planRepository;
    private InterviewPlanService service;

    @BeforeEach
    void setUp() {
        chatModel = Mockito.mock(ChatModel.class);
        materialProfileRepository = Mockito.mock(MaterialProfileRepository.class);
        planRepository = Mockito.mock(InterviewPlanRepository.class);
        service = new InterviewPlanService(chatModel, materialProfileRepository, planRepository, new ObjectMapper());
    }

    /** 驱动条件：库中无 JD 画像（source=USER）+ 规划模型返回合法考点数组 */
    private void mockPlannerReturnsTopics() {
        when(materialProfileRepository.existsBySessionIdAndProfileType(SESSION_ID, "JD")).thenReturn(false);
        when(materialProfileRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(TOPICS_JSON)))));
    }

    @Nested
    @DisplayName("正常路径")
    class 正常路径 {

        @Test
        @DisplayName("查无计划 → 生成并落库，返回新计划（source=USER）")
        void 查无计划正常生成() {
            mockPlannerReturnsTopics();
            when(planRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());
            when(planRepository.save(any(InterviewPlan.class))).thenAnswer(inv -> inv.getArgument(0));

            InterviewPlan plan = service.ensurePlan("我想练 Java 后端面试", SESSION_ID, CANDIDATE_ID);

            assertEquals(InterviewPlanService.SOURCE_USER, plan.getSource());
            assertTrue(plan.getTopicsJson().contains("线程池参数"));
        }
    }

    @Nested
    @DisplayName("并发兜底（唯一约束拦截双计划）")
    class 并发兜底 {

        @Test
        @DisplayName("生成落库撞唯一约束 → 读回先落库的那份返回，丢弃自己生成的那份")
        void 撞唯一约束读回已有计划() {
            mockPlannerReturnsTopics();
            InterviewPlan winner = new InterviewPlan(SESSION_ID, CANDIDATE_ID, TOPICS_JSON,
                    InterviewPlanService.SOURCE_USER, LocalDateTime.now());
            when(planRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.empty())          // ensurePlan 开头的"查"
                    .thenReturn(Optional.of(winner));      // 撞约束后的"读回"
            when(planRepository.save(any(InterviewPlan.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_plan_session"));

            InterviewPlan plan = service.ensurePlan("我想练 Java 后端面试", SESSION_ID, CANDIDATE_ID);

            // 返回的必须是先落库的那份（同一实例），而不是自己新生成的那份
            assertSame(winner, plan);
        }

        @Test
        @DisplayName("撞约束且读回不到（异常态）→ IllegalStateException 向上抛，由全局异常处理归口")
        void 撞约束读不到抛异常() {
            mockPlannerReturnsTopics();
            when(planRepository.findBySessionId(anyString())).thenReturn(Optional.empty());
            when(planRepository.save(any(InterviewPlan.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_plan_session"));

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> service.ensurePlan("我想练 Java 后端面试", SESSION_ID, CANDIDATE_ID));
        }
    }
}
