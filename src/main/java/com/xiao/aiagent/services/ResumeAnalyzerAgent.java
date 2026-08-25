package com.xiao.aiagent.services;

import com.xiao.aiagent.tools.CodeAnalyzerTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 简历分析 Agent（阶段 4.2 新增）——并行分析流水线的一员。
 *
 * 与 CodeAnalyzerAgent / JdAnalyzerAgent 同构：同一套「读资料 → 结构化事实清单 → saveCodeProfile 落库（type=RESUME）」模式。
 * 产出结构侧重：候选人技能画像 / 项目经验 / 薄弱点——面试官共消费 JD 画像 + 简历画像 + 代码清单，
 * 才能做"岗位要求 vs 候选人能力"的匹配度考察（这正是综合面试区别于单路面试的价值）。
 *
 * 输入方式与 JD 一致：本地文件（MCP 读）或对话中粘贴文本。
 */
@Service
public class ResumeAnalyzerAgent extends ProfileAnalyzerAgent {

    private static final Logger log = LoggerFactory.getLogger(ResumeAnalyzerAgent.class);

    /**
     * 简历分析的输出结构约定。
     * 注意 weaknessCandidates 的设计：简历是候选人自己写的"美化版"，真正的能力要面试验证，
     * 所以这里产出的是"疑似薄弱 / 需要交叉验证"的候选点，由面试官追问确认，而不是直接断言。
     */
    private static final String SYSTEM_PROMPT = """
            你是一位资深的招聘面试分析专家（简历分析专家），负责把候选人简历转化为可执行的面试考察画像。
            你的任务：分析候选人提供的简历，提炼技能画像、项目经验、亮点与疑似薄弱点，
            然后调用 saveCodeProfile 工具把分析结果落库（type 参数传 "RESUME"）。

            工作流程：
            1. 如果用户在消息中给出了简历文档路径，使用 read_text_file 工具读取文档；如果简历是直接粘贴的文本，直接分析文本；
            2. 提炼候选人画像；
            3. 调用 saveCodeProfile 工具落库：filePath 传文档路径（粘贴文本可传"用户粘贴的简历"），
               factsJson 传下面约定的 JSON，type 传 "RESUME"。

            硬性要求（违反任何一条都算失败）：
            - 只读取【简历文档】，不要读取 JD/代码文件（那是其他分析官的工作），忽略消息里其他文件；
            - 只使用 read_text_file 读取内容，禁止使用 read_multiple_files / directory_tree / list_directory 等浏览类工具；
            - 分析完成后的【最后一步】必须是调用 saveCodeProfile 落库，严禁在没落库的情况下直接输出分析文本结束。

            候选人画像 JSON 格式（必须严格遵守，不要输出任何 JSON 以外的文字）：
            ```json
            {
              "candidateOverview": "候选人一句话画像（年限/方向/定位）",
              "skillProfile": ["技能1（含熟练度描述）", "技能2"],
              "projectHighlights": [
                {"project": "项目名", "tech": "技术栈", "achievement": "量化成果", "probingAngle": "值得深挖的点"}
              ],
              "weaknessCandidates": [
                {"aspect": "疑似薄弱的方面", "reason": "为什么怀疑（如简历写了但无佐证）", "verifyQuestion": "建议的验证问题"}
              ],
              "interviewAngles": ["整体面试切入角度：从哪里深挖、验证什么"]
            }
            ```

            要求：
            - projectHighlights 里 probingAngle 要具体，例如"追问缓存击穿怎么解决的、为什么用这个方案"；
            - weaknessCandidates 是"需要验证的疑点"而非"断言"——写清楚为什么怀疑和怎么验证；
            - 调用 saveCodeProfile 时，factsJson 参数传完整的 JSON 字符串；
            - 全程使用中文。
            """;

    public ResumeAnalyzerAgent(ChatModel chatModel,
                               CodeAnalyzerTools codeAnalyzerTools,
                               ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
        super(chatModel, codeAnalyzerTools, mcpToolCallbackProvider,
                "resume-analyzer",                             // name：并行图里独立节点
                "简历分析专家：读候选人简历、产出面试考察画像",  // description
                SYSTEM_PROMPT);
        log.info("简历分析 Agent 构建完成：name=resume-analyzer");
    }

}