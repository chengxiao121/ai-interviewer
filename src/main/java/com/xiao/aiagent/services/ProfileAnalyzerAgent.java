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

import java.util.ArrayList;
import java.util.List;

/**
 * 分析型 Agent 基类（阶段 4.2 重构）——三个分析官的共同骨架。
 *
 * Rule of Three 触发点：代码分析 / JD 分析 / 简历分析出现同构结构三次
 * （名字+描述+prompt → 拼 ReactAgent → 注入 MCP 读文件 → getAgent 透出 / analyze 同步执行），
 * 于是把共同骨架抽到本基类，子类只负责三样【各自私有】的东西：
 *   ① name            —— 路由/日志标识（必须唯一，例如 code-analyzer / jd-analyzer / resume-analyzer）
 *   ② description     —— 给编排器（路由 LLM / 人看日志）的职责说明
 *   ③ systemPrompt    —— 各分析官的输出结构约定（这是它们唯一的本质区别）
 *
 * 基类是"模板方法"的经典用法：构造流程（拼装 ReactAgent）由父类固定，
 * 可变点（prompt）由子类通过构造参数注入，⚠️ 注意用构造参数而非抽象方法——
 * 抽象方法在父类构造器里调用有"子类字段还没初始化"的风险，构造参数没有。
 *
 * 与分析官搭档的工具：CodeAnalyzerTools.saveCodeProfile 带 type 参数，
 * 三个分析官共用同一把"落库扳机"，靠 type（CODE/JD/RESUME）区分来源。
 */
public abstract class ProfileAnalyzerAgent {

    // 日志用动态 logger：getClass() 让日志显示具体子类名（code-analyzer / jd-analyzer 等）
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 资料守门（阶段 5.5 新增，拼进每个分析官系统提示的末尾）。
     *
     * 为什么需要：流水线收敛后，路由注册的是【全量分析官组合】——路由 LLM 只判断
     * "是不是资料面试"，具体哪类资料在场由分析官运行时自筛。没有守门时，没资料的分析官
     * 会被迫"硬分析"：要么凭空编造分析落库（脏清单污染面试官上下文与报告），
     * 要么漫无目的翻文件（风险 19 的 directory_tree 漂移）。
     * 守门把"没资料"变成一条显式的、极其简单的执行路径（直接结束），漂移面最小化。
     */
    private static final String MATERIAL_GATE = """

            【资料守门】启动后第一步先判断：上下文中是否存在你负责的那一类资料（明确的文件路径，或用户直接粘贴的资料文本）。
            若不存在：直接回复"无我负责的资料，跳过分析"并立即结束——禁止调用任何工具（包括 read_text_file
            等一切读取工具）、禁止调用 saveCodeProfile 落库、禁止凭想象编造任何分析内容。
            若存在：按上方流程正常执行。本守门优先级最高，与其他任何要求冲突时以本条为准。
            """;

    // ReactAgent：拼装好的"分析循环"（构造时 build 一次，之后每次请求复用）
    private final ReactAgent agent;
    // 系统提示：analyze() 同步分析时要拼进初始消息，基类自存一份（子类的私有人设由构造参数传入）
    private final String systemPrompt;

    protected ProfileAnalyzerAgent(ChatModel chatModel,
                                   CodeAnalyzerTools analyzerTools,
                                   ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
                                   String name,
                                   String description,
                                   String systemPrompt) {

        // 守门拼在子类私有 prompt 之后（字段保存拼后的完整版：analyze() 同步路径同样受守门约束）
        this.systemPrompt = systemPrompt + MATERIAL_GATE;

        // MCP 工具注入：与 InterviewAssistant 一样的降级兼容策略
        // 未配置 MCP 或 npx 拉包失败时 getIfAvailable() 返回 null，Agent 只用内置 saveCodeProfile
        // 但那样就读不了资料了——分析官读文件强依赖 MCP read_file，所以这里打 WARN 提醒
        ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
        if (mcpProvider != null) {
            log.info("MCP 工具已注入分析 Agent[{}]，可用工具数：{}", name, mcpProvider.getToolCallbacks().length);
        } else {
            log.warn("未检测到 MCP 工具，分析 Agent[{}] 无法读取文件！", name);
        }

        // ── 拼装"分析循环"── 与分析官搭档的工具是 CodeAnalyzerTools（saveCodeProfile 落库）
        Builder builder = ReactAgent.builder()
                .name(name)                       // agent 名，图日志里显示（编排时能看到这个节点）
                .description(description)         // 职责描述（ParallelAgent 节点、日志里能看见）
                .systemPrompt(systemPrompt)       // 【大脑人设】各分析官的输出结构约定
                .model(chatModel)                 // 【大脑本体】同一个 qwen 模型
                .methodTools(analyzerTools)       // 【武器】saveCodeProfile（落库，type 区分来源）
                .outputKey(name)                  // 【并行合并要件】ParallelAgent 要求每个子 Agent 的
                                                  // outputKey 唯一（实测：不设置会打 "no outputKey defined"
                                                  // 警告；三个都设成 messages 会被校验拒绝 "Duplicate output
                                                  // keys"）。这个名字本身唯一（jd-analyzer/resume-analyzer/
                                                  // code-analyzer），框架把每个分析官的最终结果写到各自的
                                                  // outputKey，ConcatenationMergeStrategy 才有内容可合并。
                                                  // 注意：这不影响 messages 键的对话流（数据契约主路
                                                  // code_profile 落库 + getCodeFacts 取，见类注释）。
                .enableLogging(true);             // 打开节点日志：验收要求"日志可见每个分析节点"

        if (mcpProvider != null) {
            builder.toolCallbackProviders(mcpProvider);   // MCP 外部工具（read_file/list_directory 等）
        }

        this.agent = builder.build();
    }

    /**
     * 暴露内部的 ReactAgent 给编排器使用。
     * 与 InterviewAssistant.getAgent() 同一模式：Service 包装管理生命周期，需要被编排时透出 Agent。
     */
    public ReactAgent getAgent() {
        return agent;
    }

    /**
     * 执行一次资料分析（同步调用）。
     * 用于"独立分析"场景：编排器判断该会话还没分析过，先同步跑一次分析落库，再启动面试官对话。
     *
     * @param userMessage 用户原始消息（含文件路径或粘贴的文档文本，分析官会自己识别并调 read_file）
     * @param sessionId   会话 id，作为 threadId 传给 RunnableConfig（工具落库时经 SessionKeys 取根会话）
     */
    public void analyze(String userMessage, String sessionId) {

        // 组装初始消息：系统提示 + 用户消息（分析是一次性任务，不需要注入历史与 RAG）
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userMessage));

        // threadId = sessionId：工具落库时经 SessionKeys 取根会话 id（与 InterviewAssistant 同一套）
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();

        log.info("分析 Agent 启动：sessionId={}, message={}", sessionId,
                userMessage.length() > 80 ? userMessage.substring(0, 80) + "..." : userMessage);

        try {
            // 同步调用：跑完整个 ReAct 循环（读资料 → 分析 → 调 saveCodeProfile 落库）
            // invoke 返回最终 state，我们这里不需要取返回值——落库已由工具完成
            agent.invoke(messages, config);
            log.info("分析 Agent 完成：sessionId={}", sessionId);
        } catch (GraphRunnerException e) {
            log.error("分析 Agent 执行失败：sessionId={}", sessionId, e);
            throw new RuntimeException("资料分析失败", e);
        }
    }

}