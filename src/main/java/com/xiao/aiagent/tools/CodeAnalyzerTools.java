package com.xiao.aiagent.tools;

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
     * 保存分析事实清单到数据库。
     * 分析 Agent 读完资料、产出结构化 JSON 后调用此工具落库。
     *
     * 设计要点——为什么用 @Tool 落库而不是外层代码解析落库？
     *   因为后面要用 ParallelAgent/SequentialAgent 编排，编排 Agent 自动按序执行子 Agent，
     *   外层无法在"分析完、面试开始前"插入落库逻辑。
     *   用 @Tool 让 LLM 自己触发落库，和现有 scoreRecord 模式一致，编排器不用操心。
     *
     * 阶段 4.2 新增 type 参数：代码分析 / JD 分析 / 简历分析共享本工具，
     *   由模型按自己的身份传入 CODE/JD/RESUME，落库后用 profileType 区分来源。
     *
     * @param filePath    分析的源文件路径（粘贴文本可传资料名称或"用户粘贴"）
     * @param factsJson   事实清单 JSON 全文（各分析官按自己的 prompt 约定结构）
     * @param type        资料类型：CODE=代码 / JD=岗位描述 / RESUME=简历
     * @param toolContext 工具上下文（框架注入），从中取根会话 id（SessionKeys）
     * @return 落库结果提示，供 LLM 确认
     */
    @Tool(description = "保存分析事实清单到数据库。分析完资料后必须调用此工具，把结构化分析结果落库，供面试官取用。CODE 表示代码、JD 表示岗位描述、RESUME 表示简历。")
    public String saveCodeProfile(
            @ToolParam(description = "分析的源文件路径（代码文件绝对路径，或 JD/简历文档路径；粘贴文本可传资料名称）") String filePath,
            @ToolParam(description = "事实清单 JSON 字符串（各分析官按自己的定义输出，包含技术栈/实现细节/可追问点等）") String factsJson,
            @ToolParam(description = "资料类型：CODE=代码 / JD=岗位描述 / RESUME=简历，必须与你的身份一致") String type,
            ToolContext toolContext) {

        // 会话 key 取【根 sessionId】（子图会给 threadId 自动加 _subgraph_ 后缀，详见 SessionKeys；
        // 与 InterviewTools 各工具同一套机制）
        String sessionId = SessionKeys.rootSessionId(toolContext);

        log.info("工具调用 saveCodeProfile：sessionId={}, type={}, filePath={}, jsonLength={}",
                sessionId, type, filePath, factsJson != null ? factsJson.length() : 0);

        CodeProfile profile = new CodeProfile(sessionId, filePath, type, factsJson, LocalDateTime.now());
        CodeProfile saved = codeProfileRepository.save(profile);

        log.info("事实清单落库成功：id={}, sessionId={}, type={}, filePath={}", saved.getId(), sessionId, type, filePath);

        return String.format("事实清单已落库（记录 id=%d, 类型=%s），面试官可据此出题。", saved.getId(), type);
    }

}
