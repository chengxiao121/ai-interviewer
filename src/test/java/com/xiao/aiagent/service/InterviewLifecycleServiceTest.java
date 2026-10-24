package com.xiao.aiagent.service;

import com.xiao.aiagent.entity.InterviewSession;
import com.xiao.aiagent.repository.InterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InterviewLifecycleService 单测（阶段 11：会话状态机的回归锁定）。
 *
 * 锁定三分支：
 * ① finish——正常结束（写库）/ 幂等（已结束不再写）/ 未入场抛 IAE（400 语义）；
 * ② isFinished——FINISHED=true / IN_PROGRESS=false / 无绑定=false。
 */
@DisplayName("InterviewLifecycleService：结束面试与会话状态判定")
class InterviewLifecycleServiceTest {

    private static final String SESSION_ID = "s1";

    private InterviewSessionRepository repository;
    private InterviewLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(InterviewSessionRepository.class);
        service = new InterviewLifecycleService(repository);
    }

    private InterviewSession sessionWithStatus(String status) {
        InterviewSession session = new InterviewSession(SESSION_ID, "c1", List.of("RESUME-x"));
        session.setStatus(status);
        return session;
    }

    @Nested
    @DisplayName("finish：结束面试")
    class Finish {

        @Test
        @DisplayName("进行中 → 置 FINISHED 并落库")
        void 正常结束() {
            InterviewSession session = sessionWithStatus(InterviewSession.STATUS_IN_PROGRESS);
            when(repository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(session));
            when(repository.save(any(InterviewSession.class))).thenAnswer(inv -> inv.getArgument(0));

            InterviewSession result = service.finish(SESSION_ID);

            assertEquals(InterviewSession.STATUS_FINISHED, result.getStatus());
            verify(repository).save(session);
        }

        @Test
        @DisplayName("已结束 → 幂等原样返回，不重复写库")
        void 幂等结束() {
            InterviewSession session = sessionWithStatus(InterviewSession.STATUS_FINISHED);
            when(repository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(session));

            InterviewSession result = service.finish(SESSION_ID);

            assertSame(session, result);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("无绑定（从未入场）→ IllegalArgumentException，由全局异常处理归口 400")
        void 未入场抛异常() {
            when(repository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> service.finish(SESSION_ID));
        }
    }

    @Nested
    @DisplayName("isFinished：状态判定")
    class IsFinished {

        @Test
        @DisplayName("FINISHED → true；IN_PROGRESS → false")
        void 两态判定() {
            when(repository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(sessionWithStatus(InterviewSession.STATUS_FINISHED)));
            assertTrue(service.isFinished(SESSION_ID));

            when(repository.findBySessionId(SESSION_ID))
                    .thenReturn(Optional.of(sessionWithStatus(InterviewSession.STATUS_IN_PROGRESS)));
            assertFalse(service.isFinished(SESSION_ID));
        }

        @Test
        @DisplayName("无绑定 → false（老数据/未入场会话没有生命周期，不拦报告以外的路径）")
        void 无绑定视为未结束() {
            when(repository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());
            assertFalse(service.isFinished(SESSION_ID));
        }
    }
}
