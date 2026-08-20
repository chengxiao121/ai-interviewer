package com.xiao.aiagent.tools;

import com.xiao.aiagent.entity.ScoreRecord;
import com.xiao.aiagent.repository.ScoreRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 面试场景自定义工具集（Function Calling）。
 * 通过 @Tool 注解暴露给 ChatClient，由 LLM 在对话中按需调用。
 * 注册方式：InterviewAssistant 构造 ChatClient 时 .defaultTools(this) 注入。
 */
@Component
public class InterviewTools {

    private static final Logger log = LoggerFactory.getLogger(InterviewTools.class);

    private final ScoreRecordRepository scoreRecordRepository;

    public InterviewTools(ScoreRecordRepository scoreRecordRepository) {
        this.scoreRecordRepository = scoreRecordRepository;
    }

    /**
     * 保存单题评分到数据库。
     * 面试官每点评完一道题后调用，把题目、求职者回答、评分、点评落库，
     * 供后续 questionStats 工具做薄弱考点统计。
     *
     * @param topic     考点（如 Java并发、Redis持久化、MySQL索引），用于按考点聚合
     * @param question  题目内容
     * @param answer    求职者的回答
     * @param score     评分（0~10，可带一位小数）
     * @param feedback  面试官点评 / 改进建议
     * @param toolContext 工具上下文容器（Spring AI 注入，不是模型填的）。
     *        sessionId 由服务端在 chat() 的 .toolContext(...) 放开入，这里取出作为评分归属。
     *        难点：不能把 sessionId 写成普通 @ToolParam 参数——
     *        普通参数会进模型可见的工具 schema，模型要填、可能填错串会；
     *        ToolContext 类型被框架剔除于 schema 之外，模型看不到也改不了，会话隔离最稳。
     * @return 保存结果提示（含记录 id），供 LLM 向用户转述
     */
    @Tool(description = "保存单题评分到数据库。面试官点评完一道题后调用，把题目、回答、评分、点评落库。")
    public String scoreRecord(
            @ToolParam(description = "考点，如 Java并发、Redis持久化、MySQL索引，用于按考点聚合统计") String topic,
            @ToolParam(description = "题目内容") String question,
            @ToolParam(description = "求职者的回答") String answer,
            @ToolParam(description = "评分，0~10 分，可带一位小数") Double score,
            @ToolParam(description = "面试官点评或改进建议") String feedback,
            ToolContext toolContext) {

        // 从 ToolContext 取出服务端注入的 sessionId（和 chat() 里的 .toolContext(...) 配对：那边写，这里读）
        String sessionId = (String) toolContext.getContext().get("sessionId");
        log.info("工具调用 scoreRecord：sessionId={}, topic={}, score={}", sessionId, topic, score);

        // 防御性校验：评分必须在 0~10，越界值钳制到边界（LLM 打分偶尔会给出非法值，避免脏数据污染统计）
        double validScore = Math.max(0, Math.min(10, score));
        if (validScore != score) {
            log.warn("评分越界已钳制：原始 score={}，修正为 {}", score, validScore);
        }

        // 幂等去重：同会话同题目已评分过则更新原记录，避免模型对同一道题重复写进数据库污染统计
        Optional<ScoreRecord> existing = scoreRecordRepository.findBySessionIdAndQuestion(sessionId, question);
        if (existing.isPresent()) {
            ScoreRecord record = existing.get();
            record.setTopic(topic);
            record.setAnswer(answer);
            record.setScore(validScore);
            record.setFeedback(feedback);
            record.setCreatedAt(LocalDateTime.now());
            ScoreRecord saved = scoreRecordRepository.save(record);
            log.info("同题重复评分，已更新原记录：id={}, topic={}, score={}", saved.getId(), topic, validScore);
            return String.format("已更新这道题（记录 id=%d）的评分：考点【%s】，得分 %.1f 分。",
                    saved.getId(), topic, validScore);
        }

        ScoreRecord record = new ScoreRecord(
                sessionId, topic, question, answer, validScore, feedback, LocalDateTime.now());
        ScoreRecord saved = scoreRecordRepository.save(record);

        log.info("评分落库成功：id={}, topic={}, score={}", saved.getId(), topic, validScore);

        return String.format("已记录评分：考点【%s】，得分 %.1f 分，记录 id=%d。下次可直接查询薄弱考点统计。",
                topic, validScore, saved.getId());
    }

    /**
     * 查询某会话的答题统计：按考点聚合答题数、平均分、最低分，并标注薄弱考点。
     * 用户问「我有哪些薄弱考点」「答题统计」时调用。
     *
     * @param toolContext 工具上下文，sessionId 由服务端注入
     * @return 聚合统计文本，供 LLM 向用户转述
     */
    @Tool(description = "查询某会话的答题统计：各考点的答题数、平均分、最低分，并列出薄弱考点。用户问薄弱考点或统计时调用。")
    public String questionStats(ToolContext toolContext) {

        String sessionId = (String) toolContext.getContext().get("sessionId");
        log.info("工具调用 questionStats：sessionId={}", sessionId);

        List<Object[]> rows = scoreRecordRepository.aggregateByTopic(sessionId);
        if (rows.isEmpty()) {
            return "当前会话还没有评分记录，先回答几道题拿到评分后再来查询薄弱点。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("按考点统计（平均分从低到高）：\n");
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
            sb.append("没有平均分低于 6 分的薄弱考点，整体表现良好。");
        } else {
            sb.append("薄弱考点（平均分低于 6 分）：").append(String.join("、", weakTopics));
        }
        return sb.toString();
    }

    /**
     * 纯函数计算工具：做精确的数值运算（平均/求和/百分比），外包给工具避免模型算术幻觉。
     * 用户问「平均分」「正确率」等需要算数的场景时调用。
     *
     * @param values 数值列表（如各题得分）
     * @param op     运算类型：average 平均 / sum 求和 / percentage 百分比（按满分 10 折算）
     * @return 计算结果文本
     */
    @Tool(description = "纯函数计算：对数值列表做平均(average)、求和(sum)或百分比(percentage，按满分10折算)。需要精确算术时调用，避免算错。")
    public String calculator(
            @ToolParam(description = "数值列表，如各道题的得分 [8.5, 7.5, 9, 6, 4]") List<Double> values,
            @ToolParam(description = "运算类型：average 求平均 / sum 求和 / percentage 求百分比（数值按满分 10 折算成百分比）") String op) {

        log.info("工具调用 calculator：op={}, values={}", op, values);

        if (values == null || values.isEmpty()) {
            return "没有可计算的数值，请提供至少一个数。";
        }

        double sum = 0;
        for (Double v : values) {
            sum += v;
        }

        switch (op) {
            case "sum" -> {
                return String.format("求和结果：%.2f", sum);
            }
            case "percentage" -> {
                double pct = sum / values.size() / 10.0 * 100.0;   // 每题满分 10，换算成百分比
                return String.format("平均得分 %.2f / 10，正确率 %.1f%%", sum / values.size(), pct);
            }
            case "average", "" -> {
                return String.format("平均分：%.2f", sum / values.size());
            }
            default -> {
                return String.format("不支持的运算类型：%s，仅支持 average/sum/percentage。", op);
            }
        }
    }

}
