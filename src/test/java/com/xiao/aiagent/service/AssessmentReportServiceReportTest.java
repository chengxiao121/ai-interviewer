package com.xiao.aiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.entity.AssessmentReport;
import com.xiao.aiagent.entity.InterviewPlan;
import com.xiao.aiagent.repository.AssessmentReportRepository;
import com.xiao.aiagent.repository.InterviewPlanRepository;
import com.xiao.aiagent.repository.ScoreRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssessmentReportService.report 缓存与落库单测（报告持久化改造的回归锁定）。
 *
 * 锁定四个分支：
 * ① 缓存命中——不再调叙事 LLM、不再落库，直接反序列化返回；
 * ② 缓存 miss——generate 生成并落库（version=1）；
 * ③ force=true——忽略缓存重新生成，覆盖更新（version+1）；
 * ④ 并发首次落库撞 uk_report_session 唯一约束——读回先落库者返回（谁先谁赢）。
 * 另锁两个边界：candidateId 口径不一致仍返回既有报告；库内 JSON 损坏按 miss 自愈。
 */
@DisplayName("AssessmentReportService.report：报告缓存命中与落库")
class AssessmentReportServiceReportTest {

    private static final String SESSION_ID = "s1";
    private static final String CANDIDATE_ID = "c1";
    /** 模型输出的结论三件套（generateConclusion 可解析的最小形态） */
    private static final String CONCLUSION_JSON =
            "{\"overallSummary\":\"总体稳定\",\"matchConclusion\":\"覆盖良好\",\"recommendations\":[\"复习并发\"]}";

    private ChatModel chatModel;
    private InterviewPlanRepository interviewPlanRepository;
    private ScoreRecordRepository scoreRecordRepository;
    private WeaknessProfileService weaknessProfileService;
    private InterviewPlanService interviewPlanService;
    private InterviewLifecycleService interviewLifecycleService;
    private AssessmentReportRepository assessmentReportRepository;
    private AssessmentReportService service;

    @BeforeEach
    void setUp() {
        chatModel = Mockito.mock(ChatModel.class);
        interviewPlanRepository = Mockito.mock(InterviewPlanRepository.class);
        scoreRecordRepository = Mockito.mock(ScoreRecordRepository.class);
        weaknessProfileService = Mockito.mock(WeaknessProfileService.class);
        interviewPlanService = Mockito.mock(InterviewPlanService.class);
        interviewLifecycleService = Mockito.mock(InterviewLifecycleService.class);
        assessmentReportRepository = Mockito.mock(AssessmentReportRepository.class);
        service = new AssessmentReportService(chatModel, interviewPlanRepository, scoreRecordRepository,
                weaknessProfileService, interviewPlanService, new ObjectMapper(), assessmentReportRepository,
                interviewLifecycleService);
        // 门禁默认放行（本测试类聚焦缓存与落库；门禁分支在专门的 @Nested 里锁定）
        Mockito.when(interviewLifecycleService.isFinished(Mockito.anyString())).thenReturn(true);
    }

    /** 驱动条件：无计划 + 无评分记录（降级版空报告），结论模型返回合法三件套 */
    private void mockGeneratePathWorking() {
        when(interviewPlanRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.<InterviewPlan>empty());
        when(scoreRecordRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of());
        when(weaknessProfileService.loadProfile(CANDIDATE_ID)).thenReturn("");
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(CONCLUSION_JSON)))));
    }

    /** 一份已落库的报告实体（缓存命中场景的种子数据） */
    private AssessmentReport seededReport(String candidateId, String reportJson, int version) {
        return new AssessmentReport(SESSION_ID, candidateId, reportJson, version, LocalDateTime.now());
    }

    @Nested
    @DisplayName("缓存命中")
    class 缓存命中 {

        @Test
        @DisplayName("库中已有报告 → 直接反序列化返回，不调 LLM 不落库")
        void 命中缓存跳过生成() {
            String cachedJson = "{\"sessionId\":\"s1\",\"conclusion\":{\"overallSummary\":\"旧报告\"}}";
            when(assessmentReportRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(seededReport(CANDIDATE_ID, cachedJson, 1)));

            java.util.Map<String, Object> result = service.report(SESSION_ID, CANDIDATE_ID, false);

            assertEquals("s1", result.get("sessionId"));
            verify(chatModel, never()).call(any(Prompt.class));      // 关键断言：叙事 LLM 不被调用
            verify(assessmentReportRepository, never()).save(any()); // 关键断言：不再落库
        }

        @Test
        @DisplayName("candidateId 口径不一致 → 仍返回会话既有报告（宁旧的正确，不新的错误）")
        void 口径不一致返回既有报告() {
            String cachedJson = "{\"sessionId\":\"s1\",\"candidateId\":\"c1\"}";
            when(assessmentReportRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(seededReport(CANDIDATE_ID, cachedJson, 1)));

            java.util.Map<String, Object> result = service.report(SESSION_ID, "别的候选人", false);

            assertEquals("c1", result.get("candidateId"));
            verify(chatModel, never()).call(any(Prompt.class));
        }

        @Test
        @DisplayName("库内 JSON 损坏 → 按 miss 处理，重新生成自愈")
        void 损坏缓存自愈() {
            mockGeneratePathWorking();
            when(assessmentReportRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(seededReport(CANDIDATE_ID, "{不是合法JSON", 1)));
            when(assessmentReportRepository.save(any(AssessmentReport.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            java.util.Map<String, Object> result = service.report(SESSION_ID, CANDIDATE_ID, false);

            assertNotNull(result.get("conclusion"));
            verify(assessmentReportRepository).save(any(AssessmentReport.class)); // 自愈重新落库
        }
    }

    @Nested
    @DisplayName("缓存 miss 与 force")
    class 缓存Miss与Force {

        @Test
        @DisplayName("查无报告 → 生成并落库（version=1）")
        void 首次生成落库() {
            mockGeneratePathWorking();
            when(assessmentReportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());
            when(assessmentReportRepository.save(any(AssessmentReport.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            java.util.Map<String, Object> result = service.report(SESSION_ID, CANDIDATE_ID, false);

            assertNotNull(result.get("conclusion"));
            ArgumentCaptor<AssessmentReport> captor = ArgumentCaptor.forClass(AssessmentReport.class);
            verify(assessmentReportRepository).save(captor.capture());
            assertEquals(1, captor.getValue().getVersion());
            assertEquals(SESSION_ID, captor.getValue().getSessionId());
        }

        @Test
        @DisplayName("force=true 忽略缓存 → 重新生成覆盖更新（version 递增）")
        void force重新生成覆盖() {
            mockGeneratePathWorking();
            AssessmentReport existing = seededReport(CANDIDATE_ID, "{\"sessionId\":\"s1\"}", 1);
            when(assessmentReportRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(existing));
            when(assessmentReportRepository.save(any(AssessmentReport.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            java.util.Map<String, Object> result = service.report(SESSION_ID, CANDIDATE_ID, true);

            assertNotNull(result.get("conclusion"));
            // 覆盖更新：落库的是既有实体（非新增），version 递增为 2
            ArgumentCaptor<AssessmentReport> captor = ArgumentCaptor.forClass(AssessmentReport.class);
            verify(assessmentReportRepository).save(captor.capture());
            assertSame(existing, captor.getValue());
            assertEquals(2, existing.getVersion());
        }
    }

    @Nested
    @DisplayName("并发兜底（唯一约束拦截双报告）")
    class 并发兜底 {

        @Test
        @DisplayName("首次落库撞唯一约束 → 读回先落库的报告返回，丢弃自己生成的")
        void 撞唯一约束读回已有报告() {
            mockGeneratePathWorking();
            String winnerJson = "{\"sessionId\":\"s1\",\"winner\":true}";
            AssessmentReport winner = seededReport(CANDIDATE_ID, winnerJson, 1);
            when(assessmentReportRepository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.empty())          // readCached 的"查"：miss
                    .thenReturn(Optional.empty())          // persist 的"查已有"：无
                    .thenReturn(Optional.of(winner));      // 撞约束后的"读回"
            when(assessmentReportRepository.save(any(AssessmentReport.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_report_session"));

            java.util.Map<String, Object> result = service.report(SESSION_ID, CANDIDATE_ID, false);

            assertEquals(Boolean.TRUE, result.get("winner")); // 返回的是先落库者那份
        }

        @Test
        @DisplayName("撞约束且读回不到（异常态）→ IllegalStateException 向上抛，由全局异常处理归口")
        void 撞约束读不到抛异常() {
            mockGeneratePathWorking();
            when(assessmentReportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());
            when(assessmentReportRepository.save(any(AssessmentReport.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_report_session"));

            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> service.report(SESSION_ID, CANDIDATE_ID, false));
        }
    }

    @Nested
    @DisplayName("报告门禁（阶段 11：结束后才可看）")
    class 报告门禁 {

        @Test
        @DisplayName("面试未结束 → IllegalArgumentException（400 透传），且不查缓存不生成")
        void 未结束拒绝查看() {
            Mockito.when(interviewLifecycleService.isFinished(Mockito.anyString())).thenReturn(false);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> service.report(SESSION_ID, CANDIDATE_ID, false));
            verify(chatModel, never()).call(any(Prompt.class));
            verify(assessmentReportRepository, never()).findBySessionId(any());
        }

        @Test
        @DisplayName("force=true 同样受门禁约束（逃生口只管缓存，不管状态机）")
        void force也受门禁() {
            Mockito.when(interviewLifecycleService.isFinished(Mockito.anyString())).thenReturn(false);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> service.report(SESSION_ID, CANDIDATE_ID, true));
        }
    }
}
