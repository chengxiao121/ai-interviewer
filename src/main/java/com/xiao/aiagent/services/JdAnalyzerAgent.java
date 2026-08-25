package com.xiao.aiagent.services;

import com.xiao.aiagent.tools.CodeAnalyzerTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * JD（岗位描述）分析 Agent（阶段 4.2 新增）——并行分析流水线的一员。
 *
 * 与 CodeAnalyzerAgent 同构：同一套「读资料 → 结构化事实清单 → saveCodeProfile 落库（type=JD）」模式。
 * 唯一区别是产出的结构：代码分析产 techStack/riskPoints/followUpPoints，
 * 这里产 roleOverview/techRequirements/interviewFocus——面试官据此判断"该候选人为什么样的人、岗位要考察什么"。
 *
 * 输入方式支持两种（技术要点：JD/简历可以是本地文件（MCP 读）或对话中粘贴的文本）：
 *   - 用户在消息里给路径 → 用 read_file 读文档；
 *   - 用户直接把 JD 文本贴在对话里 → 直接分析文本（filePath 传 "用户粘贴的 JD"）。
 */
@Service
public class JdAnalyzerAgent extends ProfileAnalyzerAgent {

    private static final Logger log = LoggerFactory.getLogger(JdAnalyzerAgent.class);

    /**
     * JD 分析的输出结构约定。注意：JD 里"技术要求"是岗位宣称的，不等于候选人具备的，
     * 所以额外要求产出 interviewFocus（面试应重点考察哪些）——这是 JD 消费的正确姿势：
     * 岗位要求 vs 候选人的匹配度要面试官去验证，不能默认候选人都会。
     */
    private static final String SYSTEM_PROMPT = """
            你是一位资深的岗位需求分析专家（JD 分析专家），负责把岗位描述转化为可执行的面试考察矩阵。
            你的任务：分析候选人提供的岗位描述（JD），提炼岗位的核心职责、技术要求、软性要求与考察重点，
            然后调用 saveCodeProfile 工具把分析结果落库（type 参数传 "JD"）。

            工作流程：
            1. 如果用户在消息中给出了 JD 文档路径，使用 read_text_file 工具读取文档；如果 JD 是直接粘贴的文本，直接分析文本；
            2. 提炼岗位画像；
            3. 调用 saveCodeProfile 工具落库：filePath 传文档路径（粘贴文本可传"用户粘贴的 JD"），
               factsJson 传下面约定的 JSON，type 传 "JD"。

            硬性要求（违反任何一条都算失败）：
            - 只读取【JD 文档】，不要读取简历/代码文件（那是其他分析官的工作），忽略消息里其他文件；
            - 只使用 read_text_file 读取内容，禁止使用 read_multiple_files / directory_tree / list_directory 等浏览类工具；
            - 分析完成后的【最后一步】必须是调用 saveCodeProfile 落库，严禁在没落库的情况下直接输出分析文本结束。

            岗位画像 JSON 格式（必须严格遵守，不要输出任何 JSON 以外的文字）：
            ```json
            {
              "roleOverview": "岗位做什么的一句话概述",
              "coreResponsibilities": ["核心职责1", "核心职责2"],
              "techRequirements": ["技术要求1", "技术要求2"],
              "softSkills": ["软性要求1", "软性要求2"],
              "interviewFocus": ["面试应重点考察的点：技术深度/场景设计/工程落地等"],
              "potentialQuestions": ["基于该 JD 衍生出的面试问题，要贴合岗位要求"]
            }
            ```

            要求：
            - interviewFocus 要具体可操作，让面试官知道"测什么能力、怎么测"；
            - potentialQuestions 要贴近该岗位真实业务场景（如电商 JD 出"订单超卖"类问题）；
            - 调用 saveCodeProfile 时，factsJson 参数传完整的 JSON 字符串；
            - 全程使用中文。
            """;

    public JdAnalyzerAgent(ChatModel chatModel,
                           CodeAnalyzerTools codeAnalyzerTools,
                           ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
        super(chatModel, codeAnalyzerTools, mcpToolCallbackProvider,
                "jd-analyzer",                          // name：并行图里独立节点
                "JD 分析专家：读岗位描述、产出面试考察矩阵",  // description
                SYSTEM_PROMPT);
        log.info("JD 分析 Agent 构建完成：name=jd-analyzer");
    }

}