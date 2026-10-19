package com.xiao.aiagent.services;

import com.xiao.aiagent.repository.ScoreRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WeaknessProfileService.loadProfile 单测（P1 测试补齐）。
 *
 * loadProfile 是"薄弱点摘要文本"的唯一数据源（工具返回 + 开场注入共用同一段话），
 * 格式化与聚合口径（平均分 < 6 记为薄弱）一旦漂移会同时污染两个消费方。
 * 用 Mockito mock 仓储返回聚合行（Object[]{topic, count, avg, min}），纯测聚合与格式化逻辑。
 */
@DisplayName("WeaknessProfileService.loadProfile：跨会话薄弱点聚合")
class WeaknessProfileServiceTest {

    private ScoreRecordRepository repository;
    private WeaknessProfileService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ScoreRecordRepository.class);
        service = new WeaknessProfileService(repository);
    }

    private static Object[] row(String topic, long count, double avg, double min) {
        return new Object[]{topic, count, avg, min};
    }

    @Test
    @DisplayName("无评分记录 → 返回空串（调用方跳过注入）")
    void 无记录返回空串() {
        Mockito.when(repository.aggregateByTopicForCandidate("c1")).thenReturn(List.of());
        assertEquals("", service.loadProfile("c1"));
    }

    @Test
    @DisplayName("全部考点及格 → 摘要标注'暂无薄弱考点'")
    void 全部及格标注无薄弱() {
        Mockito.when(repository.aggregateByTopicForCandidate("c1")).thenReturn(List.of(
                row("线程池", 3, 7.5, 6.0),
                row("JVM", 2, 8.0, 7.0)));
        String text = service.loadProfile("c1");
        assertTrue(text.contains("该候选人此前历次面试的答题统计"));
        assertTrue(text.contains("线程池：答题 3 道，平均 7.5 分，最低 6.0 分"));
        assertTrue(text.contains("暂无薄弱考点"));
    }

    @Test
    @DisplayName("有低于 6 分的考点 → 列入薄弱考点并给出考察指令")
    void 低于6分记为薄弱() {
        Mockito.when(repository.aggregateByTopicForCandidate("c1")).thenReturn(List.of(
                row("线程池", 3, 4.2, 3.0),
                row("JVM", 2, 8.0, 7.0)));
        String text = service.loadProfile("c1");
        assertTrue(text.contains("薄弱考点（平均分低于 6 分）：线程池"));
        assertTrue(text.contains("重点考察"));
        // 及格考点不得混入薄弱列表
        assertTrue(!text.contains("薄弱考点（平均分低于 6 分）：JVM"));
    }

    @Test
    @DisplayName("平均分恰为 6.0 → 边界语义：不算薄弱（条件是严格小于 6）")
    void 恰好6分不算薄弱() {
        Mockito.when(repository.aggregateByTopicForCandidate("c1"))
                .thenReturn(List.<Object[]>of(row("集合", 2, 6.0, 5.0)));
        String text = service.loadProfile("c1");
        assertTrue(text.contains("暂无薄弱考点"));
    }

    @Test
    @DisplayName("多个薄弱考点 → 用顿号连接")
    void 多薄弱考点顿号连接() {
        Mockito.when(repository.aggregateByTopicForCandidate("c1")).thenReturn(List.of(
                row("线程池", 2, 3.0, 2.0),
                row("并发", 1, 5.5, 5.5),
                row("JVM", 4, 9.0, 8.0)));
        String text = service.loadProfile("c1");
        assertTrue(text.contains("薄弱考点（平均分低于 6 分）：线程池、并发。"));
    }

    @Test
    @DisplayName("摘要含候选人 id（跨会话维度可追溯）")
    void 摘要含候选人id() {
        Mockito.when(repository.aggregateByTopicForCandidate("cand-42"))
                .thenReturn(List.<Object[]>of(row("线程池", 1, 5.0, 5.0)));
        assertTrue(service.loadProfile("cand-42").contains("cand-42"));
    }
}
