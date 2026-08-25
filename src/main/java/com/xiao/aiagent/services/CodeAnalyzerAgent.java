package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.xiao.aiagent.tools.CodeAnalyzerTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 代码分析 Agent——双 Agent 流水线（4.1）与并行分析流水线（4.2）共用的【分析官：代码专用】。
 *
 * 阶段 4.2 重构后本类只剩三样【私有】的东西（构建公共骨架见父类 ProfileAnalyzerAgent）：
 *   ① name = "code-analyzer"     —— 路由/日志里独立标识这个节点；
 *   ② description                —— 给编排器看的职责说明；
 *   ③ SYSTEM_PROMPT              —— 分析官之间唯一的本质区别：输出什么结构。
 *
 * 角色：分析型 Agent。读候选人代码 → 产出结构化事实清单 JSON → 调 saveCodeProfile 落库（type=CODE）。
 * 与 InterviewAssistant（对话型）的分工：本 Agent 只负责"理解代码、产出结构化数据"，
 * 不与求职者对话、不出题、不打分；面试官 Agent 负责基于事实清单出题追问。
 *
 * ── 如何理解（心智模型）────────────────────────────
 * 把它想成"代码审查员"：拿到一份代码文件，读完后写一份结构化审查报告（JSON）落库，
 * 然后工作就结束了——后面怎么用这份报告，是面试官的事，审查员不管。
 */
@Service
public class CodeAnalyzerAgent extends ProfileAnalyzerAgent {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalyzerAgent.class);

    /**
     * 代码分析的输出结构约定。
     * 对比 InterviewAssistant 的 prompt（教 LLM "怎么说话、怎么出题"），
     * 这里的 prompt 核心是教 LLM "输出什么结构"——约束它产出严格 JSON，落库时 type 传 CODE。
     * 即使如此 LLM 仍可能出错，但清单只被 LLM 消费，坏 JSON 不阻塞面试（判据：LLM 消费传原文）。
     */
    private static final String SYSTEM_PROMPT = """
            你是一位资深代码分析专家，负责阅读候选人的代码并产出结构化的事实清单。
            你的任务：读取指定代码文件，分析其技术栈、设计模式、实现细节、潜在风险点和可追问点，
            然后调用 saveCodeProfile 工具把分析结果落库（type 参数传 "CODE"）。

            工作流程：
            1. 使用 read_text_file 工具读取候选人指定的代码文件（如果用户给了路径就直接读）；
            2. 仔细分析代码，提炼事实清单；
            3. 调用 saveCodeProfile 工具，把事实清单以 JSON 字符串形式传入（type="CODE"），完成落库。

            硬性要求（违反任何一条都算失败）：
            - 只读取【代码文件】，不要读取 JD/简历文档（那是其他分析官的工作），忽略消息里其他文件；
            - 只使用 read_text_file 读取内容，禁止使用 read_multiple_files / directory_tree / list_directory / search_files 等浏览类工具；
            - 分析完成后的【最后一步】必须是调用 saveCodeProfile 落库，严禁在没落库的情况下直接输出分析文本结束。

            事实清单 JSON 格式（必须严格遵守，不要输出任何 JSON 以外的文字）：
            ```json
            {
              "techStack": ["Spring AI", "JPA", "Redis"],
              "designPatterns": ["Repository 模式", "Builder 模式"],
              "implementationDetails": [
                "使用 JedisRedisChatMemoryRepository 持久化短期记忆",
                "评分按 sessionId+question 幂等去重"
              ],
              "riskPoints": [
                {"point": "幂等去重靠 question 文本精确匹配，题目措辞微调就会重复落库", "severity": "中"},
                {"point": "评分越界只做了钳制，没有告警", "severity": "低"}
              ],
              "followUpPoints": [
                "幂等去重用 question 文本匹配有什么风险？换成长度+哈希如何？",
                "为什么用 SETNX？高可用场景有什么风险？"
              ]
            }
            ```

            要求：
            - riskPoints 每项必须有 point（描述）和 severity（高/中/低）；
            - followUpPoints 是适合面试追问的问题，要具体、能考察深度；
            - 调用 saveCodeProfile 时，factsJson 参数传完整的 JSON 字符串；
            - 全程使用中文。
            """;

    public CodeAnalyzerAgent(ChatModel chatModel,
                             CodeAnalyzerTools codeAnalyzerTools,
                             ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {
        // 骨架（ReactAgent 拼装 / MCP 注入 / getAgent / analyze）全在父类 ProfileAnalyzerAgent
        super(chatModel, codeAnalyzerTools, mcpToolCallbackProvider,
                "code-analyzer",                       // name：编排图里独立节点
                "代码分析专家：读代码、产出结构化事实清单",  // description：给编排器看
                SYSTEM_PROMPT);                         // 人设：本分析官的唯一本质
        log.info("代码分析 Agent 构建完成：name=code-analyzer");
    }

}