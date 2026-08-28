package com.xiao.aiagent.services;

import com.xiao.aiagent.repository.ScoreRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨会话薄弱点回顾服务（阶段 4.3 新增）——"候选人薄弱点摘要文本"的唯一数据源。
 *
 * 为什么抽成独立 Service（而不是散在工具/管道里）：
 *   薄弱点摘要要被两处消费：
 *     ① getWeaknessProfile 工具（模型主动调，见 InterviewTools）；
 *     ② 开场注入（系统消息，见 StreamingPipelineSupport / InterviewAssistant）。
 *   两处若各写一遍"聚合 + 格式化"，口径容易漂移（一处改一处不改）；
 *   收敛到本类后：工具返回的文本 = 注入的 system 文本 = 同一段话，单一事实来源。
 *   与 KnowledgeSearchService（题库检索文本唯一数据源）是同一套"物料装配"模式。
 */
@Service
public class WeaknessProfileService {

    private static final Logger log = LoggerFactory.getLogger(WeaknessProfileService.class);

    private final ScoreRecordRepository scoreRecordRepository;

    public WeaknessProfileService(ScoreRecordRepository scoreRecordRepository) {
        this.scoreRecordRepository = scoreRecordRepository;
    }

    /**
     * 取某候选人【跨会话】的薄弱点摘要文本。
     * 按 candidateId 聚合其所有场次的评分（答题数/平均分/最低分），标注平均分低于 6 分的薄弱考点。
     * 无任何评分记录时返回空串（调用方据此跳过注入 / 提示）。
     *
     * @param candidateId 候选人 id
     * @return 可注入 system 消息 / 直接给 LLM 转述的摘要文本；无记录返回 ""
     */
    public String loadProfile(String candidateId) {
        List<Object[]> rows = scoreRecordRepository.aggregateByTopicForCandidate(candidateId);
        if (rows.isEmpty()) {
            log.info("候选人 {} 暂无评分记录，跳过薄弱点回顾", candidateId);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【跨会话薄弱点回顾｜候选人 ").append(candidateId).append("】该候选人此前历次面试的答题统计：\n");
        List<String> weakTopics = new ArrayList<>();
        for (Object[] row : rows) {
            String topic = (String) row[0];
            long count = ((Number) row[1]).longValue();
            double avg = ((Number) row[2]).doubleValue();
            double min = ((Number) row[3]).doubleValue();
            sb.append(String.format("- %s：答题 %d 道，平均 %.1f 分，最低 %.1f 分%n", topic, count, avg, min));
            if (avg < 6.0) {
                weakTopics.add(topic);
            }
        }
        if (weakTopics.isEmpty()) {
            sb.append("该候选人暂无薄弱考点（各考点平均分均不低于 6 分）。");
        } else {
            sb.append("薄弱考点（平均分低于 6 分）：").append(String.join("、", weakTopics))
              .append("。本次面试应主动回顾这些薄弱考点并重点考察。");
        }
        return sb.toString();
    }
}