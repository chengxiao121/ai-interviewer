package com.xiao.aiagent.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiao.aiagent.entity.ScoreRecord;
import com.xiao.aiagent.repository.ScoreRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnswerEvaluatorService.persist 并发兜底单测（事务与并发安全修复的回归锁定）。
 *
 * persist 是私有方法，经公有入口 evaluateTurn 驱动：mock ChatModel 返回"是作答"的结构化评估，
 * mock 仓储模拟"查→判→写"各分支。重点锁定并发兜底分支：
 * 先查为空 → 插入撞唯一约束 uk_score_session_question → 读回先落库的记录转更新（不允许双写）。
 */
@DisplayName("AnswerEvaluatorService.persist：评分落库幂等与并发兜底")
class AnswerEvaluatorServicePersistTest {

    private static final String SESSION_ID = "s1";
    private static final String CANDIDATE_ID = "c1";
    private static final String QUESTION = "请介绍一下线程池的核心参数？";
    private static final String EVAL_JSON = """
            {"isAnswer":true,"topic":"线程池参数","question":"请介绍一下线程池的核心参数？",
             "score":7.5,"strengths":"能说明核心参数","gaps":"拒绝策略不熟","nextSuggestion":"追问拒绝策略"}
            """;

    private ChatModel chatModel;
    private ChatMemory chatMemory;
    private InterviewPlanService interviewPlanService;
    private ScoreRecordRepository repository;
    private AnswerEvaluatorService service;

    @BeforeEach
    void setUp() {
        chatModel = Mockito.mock(ChatModel.class);
        chatMemory = Mockito.mock(ChatMemory.class);
        interviewPlanService = Mockito.mock(InterviewPlanService.class);
        repository = Mockito.mock(ScoreRecordRepository.class);
        service = new AnswerEvaluatorService(chatModel, chatMemory, interviewPlanService, repository, new ObjectMapper());
    }

    /** 驱动条件：窗口里有一条助手消息（上一问）+ 评估官返回"是作答"的 JSON；plan 传 null（自由归类） */
    private void mockEvaluatorReturnsAnswer() {
        when(chatMemory.get(SESSION_ID))
                .thenReturn(List.of(new AssistantMessage("面试官：" + QUESTION)));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(EVAL_JSON)))));
    }

    @Nested
    @DisplayName("正常路径")
    class 正常路径 {

        @Test
        @DisplayName("查无同题记录 → 插入新记录，answered=true 且带注入文本")
        void 查无记录正常插入() {
            mockEvaluatorReturnsAnswer();
            when(repository.findBySessionIdAndQuestion(SESSION_ID, QUESTION)).thenReturn(Optional.empty());
            when(repository.save(any(ScoreRecord.class))).thenAnswer(inv -> inv.getArgument(0));

            AnswerEvaluatorService.EvaluationOutcome outcome =
                    service.evaluateTurn("corePoolSize 是核心线程数", SESSION_ID, CANDIDATE_ID, null);

            assertTrue(outcome.answered());
            assertTrue(outcome.injectionText().contains("线程池参数"));
            verify(repository, times(1)).save(any(ScoreRecord.class));
        }
    }

    @Nested
    @DisplayName("并发兜底（唯一约束拦截双写）")
    class 并发兜底 {

        @Test
        @DisplayName("先查为空但插入撞唯一约束 → 读回先落库的记录转更新，不产生第二条")
        void 撞唯一约束转更新() {
            mockEvaluatorReturnsAnswer();
            ScoreRecord existing = new ScoreRecord(SESSION_ID, CANDIDATE_ID, "旧考点", QUESTION,
                    "旧回答", 5.0, "旧点评", LocalDateTime.now().minusMinutes(1));
            when(repository.findBySessionIdAndQuestion(SESSION_ID, QUESTION))
                    .thenReturn(Optional.empty())          // persist 开头的"查"
                    .thenReturn(Optional.of(existing));    // 撞约束后的"读回"
            when(repository.save(any(ScoreRecord.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_score_session_question"))
                    .thenAnswer(inv -> inv.getArgument(0));

            AnswerEvaluatorService.EvaluationOutcome outcome =
                    service.evaluateTurn("corePoolSize 是核心线程数", SESSION_ID, CANDIDATE_ID, null);

            assertTrue(outcome.answered());
            // 先落库的那条被覆盖为新评分，而不是新增一条
            assertEquals(7.5, existing.getScore());
            assertEquals("线程池参数", existing.getTopic());
            assertEquals("corePoolSize 是核心线程数", existing.getAnswer());
            verify(repository, times(2)).save(any(ScoreRecord.class));
        }

        @Test
        @DisplayName("撞约束但读回不到记录（约束与查询口径不一致的异常态）→ 抛 IllegalStateException 走降级")
        void 撞约束但读不到记录抛异常() {
            mockEvaluatorReturnsAnswer();
            when(repository.findBySessionIdAndQuestion(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(repository.save(any(ScoreRecord.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_score_session_question"));

            AnswerEvaluatorService.EvaluationOutcome outcome =
                    service.evaluateTurn("corePoolSize 是核心线程数", SESSION_ID, CANDIDATE_ID, null);

            // evaluateTurn 对 persist 抛出的异常按"评估失败"降级：不评分不注入，面试照常
            assertTrue(!outcome.answered());
            assertEquals("", outcome.injectionText());
        }
    }
}
