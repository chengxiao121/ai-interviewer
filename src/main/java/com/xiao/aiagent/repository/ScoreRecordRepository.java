package com.xiao.aiagent.repository;

import com.xiao.aiagent.entity.ScoreRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScoreRecordRepository extends JpaRepository<ScoreRecord, Long> {

    /** 查某会话所有评分记录（按时间正序） */
    List<ScoreRecord> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 幂等去重：同会话同题目的已有记录。
     * 模型偶尔会对同一道题重复调用 scoreRecord 工具，命中时更新而非新增，避免污染统计。
     */
    Optional<ScoreRecord> findBySessionIdAndQuestion(String sessionId, String question);

    /**
     * 按考点聚合某会话的评分：每个考点的题数、平均分、最低分。
     * 返回 Object[]：[topic, count, avgScore, minScore]
     */
    @Query("""
            SELECT r.topic, COUNT(r), AVG(r.score), MIN(r.score)
            FROM ScoreRecord r
            WHERE r.sessionId = :sessionId
            GROUP BY r.topic
            ORDER BY AVG(r.score) ASC
            """)
    List<Object[]> aggregateByTopic(@Param("sessionId") String sessionId);

}
