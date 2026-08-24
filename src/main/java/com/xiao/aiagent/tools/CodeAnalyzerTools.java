package com.xiao.aiagent.tools;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.xiao.aiagent.entity.CodeProfile;
import com.xiao.aiagent.repository.CodeProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 代码分析 Agent 专用工具集（阶段 4.1 新增）。
 *
 * 与 InterviewTools 的区别：InterviewTools 是【面试官】的工具（评分/统计/计算），
 * 这里是【分析官】的工具（把分析结果落库）。两个 Agent 各用各的工具，职责分离。
 *
 * 当前只有一个工具 saveCodeProfile：分析 Agent 读完代码、产出事实清单 JSON 后调用，
 * 把清单落库到 code_profile 表，供面试官 Agent 按需取用。
 */
@Component
public class CodeAnalyzerTools {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalyzerTools.class);

    private final CodeProfileRepository codeProfileRepository;

    public CodeAnalyzerTools(CodeProfileRepository codeProfileRepository) {
        this.codeProfileRepository = codeProfileRepository;
    }

    /**
     * 保存代码分析事实清单到数据库。
     * 分析 Agent 读完代码、产出结构化 JSON 后调用此工具落库。
     *
     * 设计要点——为什么用 @Tool 落库而不是外层代码解析落库？
     *   因为后面要用 SequentialAgent 编排，SequentialAgent 自动按顺序执行子 Agent，
     *   外层无法在"分析完、面试开始前"插入落库逻辑。
     *   用 @Tool 让 LLM 自己触发落库，和现有 scoreRecord 模式一致，编排器不用操心。
     *
     * @param filePath    分析的代码文件路径
     * @param factsJson   事实清单 JSON 全文（技术栈/设计模式/实现细节/风险点/可追问点）
     * @param toolContext 工具上下文（框架注入），从中取 threadId 作为 sessionId
     * @return 落库结果提示，供 LLM 确认
     */
    @Tool(description = "保存代码分析事实清单到数据库。分析完代码后必须调用此工具，把结构化分析结果落库，供面试官取用。")
    public String saveCodeProfile(
            @ToolParam(description = "分析的代码文件绝对路径") String filePath,
            @ToolParam(description = "事实清单 JSON 字符串，包含 techStack(技术栈数组)/designPatterns(设计模式数组)/implementationDetails(实现细节数组)/riskPoints(风险点数组，每项含 point 和 severity)/followUpPoints(可追问点数组)") String factsJson,
            ToolContext toolContext) {

        // sessionId 取自框架注入的 RunnableConfig.threadId（与 InterviewTools.scoreRecord 同一套机制）
        String sessionId = ToolContextHelper.getConfig(toolContext)
                .flatMap(RunnableConfig::threadId)
                .orElse("unknown");

        log.info("工具调用 saveCodeProfile：sessionId={}, filePath={}, jsonLength={}",
                sessionId, filePath, factsJson != null ? factsJson.length() : 0);

        CodeProfile profile = new CodeProfile(sessionId, filePath, factsJson, LocalDateTime.now());
        CodeProfile saved = codeProfileRepository.save(profile);

        log.info("事实清单落库成功：id={}, sessionId={}, filePath={}", saved.getId(), sessionId, filePath);

        return String.format("事实清单已落库（记录 id=%d），面试官可据此出题。", saved.getId());
    }

}
