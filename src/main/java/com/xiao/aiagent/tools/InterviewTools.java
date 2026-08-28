package com.xiao.aiagent.tools;

import com.xiao.aiagent.entity.CodeProfile;
import com.xiao.aiagent.repository.CodeProfileRepository;
import com.xiao.aiagent.repository.ScoreRecordRepository;
import com.xiao.aiagent.services.WeaknessProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试官 Agent 专用工具集（Function Calling）。
 * 通过 @Tool 注解暴露给 ReactAgent，由 LLM 在对话中按需调用。
 * 注册方式：InterviewAssistant 构造 ReactAgent 时 .methodTools(this) 注入。
 *
 * 阶段 4.1 起本类与 CodeAnalyzerTools 分工：
 *   - 这里是【面试官】的工具：questionStats / getWeaknessProfile / calculator / getCodeFacts；
 *   - CodeAnalyzerTools 是【分析官】的工具：saveCodeProfile。
 *
 * 阶段 5.3 变更：scoreRecord 工具已删除——评分落库移交 AnswerEvaluatorService（代码侧
 * 确定性落库）。为什么删而不是留：工具留着，"模型工具写"与"服务代码写"两条落库路径并存，
 * 口径必然漂移；删掉后 score_record 的唯一写入方是评估服务，幂等/词表/钳制逻辑单点收口。
 */
@Component
public class InterviewTools {

    private static final Logger log = LoggerFactory.getLogger(InterviewTools.class);

    private final ScoreRecordRepository scoreRecordRepository;
    private final CodeProfileRepository codeProfileRepository;
    /** 跨会话薄弱点回顾（4.3 新增）：薄弱点摘要的唯一数据源，工具与开场注入共用同一段文本 */
    private final WeaknessProfileService weaknessProfileService;

    public InterviewTools(ScoreRecordRepository scoreRecordRepository,
                          CodeProfileRepository codeProfileRepository,
                          WeaknessProfileService weaknessProfileService) {
        this.scoreRecordRepository = scoreRecordRepository;
        this.codeProfileRepository = codeProfileRepository;
        this.weaknessProfileService = weaknessProfileService;
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

        // 会话 key 取【根 sessionId】（子图会给 threadId 自动加 _subgraph_ 后缀，详见 SessionKeys）
        String sessionId = SessionKeys.rootSessionId(toolContext);
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
     * 查询某候选人【跨会话】的答题统计（4.3 新增）——跨会话薄弱点回顾。
     * 按候选人 id 聚合他/她【所有场次】的评分：各考点答题数、平均分、最低分，标注平均分低于 6 分的薄弱考点。
     * 与 questionStats 的区别：questionStats 看"这场面试"，本工具看"这个人"（跨多场面试）。
     * 面试官在此类场景调用：
     *   - 新的一场面试开始，开场时回顾该候选人历史薄弱点，重点考察；
     *   - 用户说「继续上次的面试」「上次哪里没答好」等。
     * 实现上委托 WeaknessProfileService（4.3 单一事实来源）：模型看到的文案 = 开场注入的文案 = 同一段话。
     *
     * @param toolContext 工具上下文，candidateId 由服务端从 RunnableConfig metadata 注入
     * @return 跨会话聚合统计文本，供 LLM 向用户转述 / 注入开场考察重点
     */
    @Tool(description = "查询某候选人跨会话的答题统计（按候选人聚合所有场次的评分）：各考点答题数、平均分、最低分并标注薄弱考点。新面试开场回顾历史薄弱点、或用户说继续上次面试时调用。")
    public String getWeaknessProfile(ToolContext toolContext) {

        // 候选人 id 取【RunnableConfig metadata】（入口写入，详见 SessionKeys）
        String candidateId = SessionKeys.candidateId(toolContext);
        log.info("工具调用 getWeaknessProfile：candidateId={}", candidateId);

        // 委托 WeaknessProfileService：聚合 + 格式化唯一实现（与开场注入共用），无记录时 loadProfile 返回空串
        String profile = weaknessProfileService.loadProfile(candidateId);
        if (profile.isBlank()) {
            return String.format("候选人 %s 还没有任何评分记录，先回答几道题拿到评分后再来查询薄弱点。", candidateId);
        }
        return profile;
    }

    /**
     * 获取代码评审面试的事实清单（阶段 4.1 新增）。
     * 面试官在代码评审面试中调用，取回上游 CodeAnalyzerAgent 分析落库的结构化清单，
     * 基于清单中的技术栈/实现细节/风险点/可追问点出题追问。
     *
     * 为什么需要这个工具（与 InterviewAssistant.loadCodeFacts 的分工）：
     *   - loadCodeFacts 是外层"前置注入"：重开会话时，清单已在库，外层先查好塞进 system 消息；
     *   - 本工具是 LLM "主动拉取"：SequentialAgent 流水线首次运行时，分析 Agent 刚落库，
     *     面试官作为子 Agent 跑，外层代码插不进手，只能靠 LLM 自己调本工具取。
     * 两者都是"从 code_profile 读清单"，只是触发方式不同（前置注入 vs 主动拉取）。
     *
     * @param toolContext 工具上下文，sessionId 由服务端注入
     * @return 事实清单原文（JSON），供 LLM 出题参考
     */
    @Tool(description = "获取代码评审面试的事实清单。代码评审面试（求职者分享代码让你针对其实现提问）时调用，取回代码分析结果，基于清单中的技术栈、实现细节、风险点、可追问点出题。")
    public String getCodeFacts(ToolContext toolContext) {

        // 会话 key 取【根 sessionId】（子图会给 threadId 自动加 _subgraph_ 后缀，详见 SessionKeys）
        String sessionId = SessionKeys.rootSessionId(toolContext);
        log.info("工具调用 getCodeFacts：sessionId={}", sessionId);

        List<CodeProfile> profiles = codeProfileRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (profiles == null || profiles.isEmpty()) {
            return "当前会话还没有代码分析记录，请按普通面试流程出题。";
        }

        StringBuilder sb = new StringBuilder("分析事实清单如下（含 JD/简历/代码三类资料）：\n\n");
        for (CodeProfile profile : profiles) {
            sb.append("资料类型：").append(profile.getProfileType())
              .append("；文件：").append(profile.getFilePath()).append("\n")
              .append(profile.getFactsJson()).append("\n\n");
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
