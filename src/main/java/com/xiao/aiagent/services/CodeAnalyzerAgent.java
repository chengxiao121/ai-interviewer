package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.xiao.aiagent.tools.CodeAnalyzerTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码分析 Agent（阶段 4.1 新增）——双 Agent 流水线的【第一环】。
 *
 * 角色：分析型 Agent。读候选人代码 → 产出结构化事实清单 JSON → 调 saveCodeProfile 落库。
 * 与 InterviewAssistant（对话型）的分工：
 *   - 本 Agent 只负责"理解代码、产出结构化数据"，不与求职者对话、不出题、不打分；
 *   - 面试官 Agent 负责基于事实清单出题追问。
 *
 * ── 如何理解这个文件（心智模型）────────────────────────────
 * 把它想成"代码审查员"：拿到一份代码文件，读完后写一份结构化审查报告（JSON），
 * 报告归档到数据库（code_profile 表），然后审查员的工作就结束了——
 * 后面怎么用这份报告，是面试官的事，审查员不管。
 *
 * 与 InterviewAssistant 的技术差异：
 *   1. 不需要短期记忆（一次性任务，没有多轮对话）→ 不注入 ChatMemory、不手动读写窗口
 *   2. 不需要 RAG 题库检索（分析代码不靠题库）→ 不注入 VectorStore
 *   3. 需要 MCP 文件读取工具（read_file 读代码）→ 注入 ToolCallbackProvider
 *   4. 只需要一个内置工具（saveCodeProfile 落库）→ 注入 CodeAnalyzerTools
 * ─────────────────────────────────────────────────────────
 */
@Service
public class CodeAnalyzerAgent {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalyzerAgent.class);

    /**
     * 分析型 Agent 的系统提示——核心差异在这。
     *
     * 对比 InterviewAssistant 的 prompt（教 LLM "怎么说话、怎么出题"），
     * 这里的 prompt 核心是教 LLM "输出什么结构"——约束它产出严格 JSON。
     *
     * 三层约束保证 JSON 质量：
     *   ① 明确说"只输出 JSON，不要任何解释性文字"——减少废话前缀；
     *   ② 给出完整 schema 示例（字段名 + 类型 + 示例值）——LLM 照着填；
     *   ③ 要求用 ```json 代码块包裹——方便后续正则提取。
     * 即使如此，LLM 仍可能出错，所以第 3 步面试官取清单时还要 Jackson 容错解析（风险 15）。
     */
    private static final String SYSTEM_PROMPT = """
            你是一位资深代码分析专家，负责阅读候选人的代码并产出结构化的事实清单。
            你的任务：读取指定代码文件，分析其技术栈、设计模式、实现细节、潜在风险点和可追问点，
            然后调用 saveCodeProfile 工具把分析结果落库。

            工作流程：
            1. 使用 read_file 工具读取候选人指定的代码文件（如果用户给了路径就直接读）；
            2. 仔细分析代码，提炼事实清单；
            3. 调用 saveCodeProfile 工具，把事实清单以 JSON 字符串形式传入，完成落库。

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

    // ReactAgent：拼装好的"代码分析循环"（构造时 build 一次，之后每次请求复用）
    private final ReactAgent agent;

    public CodeAnalyzerAgent(ChatModel chatModel,
                             CodeAnalyzerTools codeAnalyzerTools,
                             ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider) {

        // MCP 工具注入：和 InterviewAssistant 一样的降级兼容策略
        // 未配置 MCP 或 npx 拉包失败时 getIfAvailable() 返回 null，Agent 只用内置 saveCodeProfile
        // 但那样就读不了代码了——分析 Agent 强依赖 MCP read_file，所以这里打 WARN 提醒
        ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
        if (mcpProvider != null) {
            log.info("MCP 工具已注入代码分析 Agent，可用工具数：{}", mcpProvider.getToolCallbacks().length);
        } else {
            log.warn("未检测到 MCP 工具，代码分析 Agent 无法读取文件！代码评审面试功能将不可用。");
        }

        // ── 拼装"代码分析循环"──
        // 对比 InterviewAssistant 的 builder：少了 saver（不需要 checkpoint）、少了面试工具
        Builder builder = ReactAgent.builder()
                .name("code-analyzer")           // agent 名，图日志里显示（SequentialAgent 编排时能看到这个节点）
                .description("代码分析专家：读代码、产出结构化事实清单")  // 描述职责
                .systemPrompt(SYSTEM_PROMPT)     // 【大脑人设】分析专家 + JSON 输出约束
                .model(chatModel)                // 【大脑本体】同一个 qwen 模型
                .methodTools(codeAnalyzerTools)  // 【武器】只有 saveCodeProfile（落库）
                .enableLogging(true);            // 打开节点日志：验收标准要求"日志可见两个 agent 节点"

        if (mcpProvider != null) {
            builder.toolCallbackProviders(mcpProvider);   // MCP 外部工具（read_file/list_directory 等）
        }

        this.agent = builder.build();
    }

    /**
     * 暴露内部的 ReactAgent 给编排器（SequentialAgent）使用。
     *
     * 为什么需要暴露？SequentialAgent.builder().subAgents(...) 要求子 Agent 是 Agent 类型，
     * 而本类是个 Service 包装。编排器需要拿到真正的 ReactAgent 实例来组装流水线。
     * 这也是"Service 包装 ReactAgent"模式的常见做法——Service 管理生命周期，需要时透出 Agent。
     */
    public ReactAgent getAgent() {
        return agent;
    }

    /**
     * 执行一次代码分析（同步调用）。
     *
     * 这个方法用于"独立分析"场景——比如编排器判断该会话还没分析过，先同步跑一次分析落库，
     * 再启动面试官流式对话。分析过程不需要给用户看（不是对话），所以用同步 invoke 而非流式。
     *
     * @param userMessage 用户原始消息（含文件路径，分析 Agent 会自己提取并调 read_file）
     * @param sessionId   会话 id，作为 threadId 传给 RunnableConfig（工具落库时取它当 sessionId）
     */
    public void analyze(String userMessage, String sessionId) {

        // 组装初始消息：系统提示 + 用户消息
        // 注意：分析 Agent 不注入历史记忆和 RAG——它是一次性任务，不需要上下文
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new UserMessage(userMessage));

        // threadId = sessionId：工具落库时取它做会话隔离（与 InterviewAssistant.chat() 同一套）
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();

        log.info("代码分析 Agent 启动：sessionId={}, message={}", sessionId,
                userMessage.length() > 80 ? userMessage.substring(0, 80) + "..." : userMessage);

        try {
            // 同步调用：分析 Agent 跑完整个 ReAct 循环（读代码 → 分析 → 调 saveCodeProfile 落库）
            // invoke 返回最终 state，我们这里不需要取返回值——落库已由工具完成
            agent.invoke(messages, config);
            log.info("代码分析 Agent 完成：sessionId={}", sessionId);
        } catch (GraphRunnerException e) {
            log.error("代码分析 Agent 执行失败：sessionId={}", sessionId, e);
            throw new RuntimeException("代码分析失败", e);
        }
    }

}
