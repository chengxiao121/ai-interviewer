package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent.ConcatenationMergeStrategy;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 综合面试流水线（阶段 4.2 新增）——并行分析的编排器。
 *
 * 结构（两层嵌套，从内到外）：
 *   ① ParallelAgent（并行）：JD 分析 / 简历分析 / 代码分析 三个分析官**同时**跑，
 *      各读各的资料、各落各的清单（code_profile 表，profileType 区分），
 *      ConcatenationMergeStrategy 把三份输出拼成大段物料写入 state.merged_facts；
 *   ② SequentialAgent（串行）：并行分析 → 面试官 Agent。
 *      面试官拿到"通用上下文 + 三个分析官的消息 + 自己调 getCodeFacts 取库"，
 *      基于 JD 画像 + 简历画像 + 代码清单一并出题。
 *
 * ── 如何理解（心智模型）────────────────────────────
 * 计划书本来的验收是「看 JD+简历+我的代码一起考我 → 三个分析并行 → 合并清单 → 面试官一并出题」。
 * 本类就是这条链路的组装者：
 *   - 为什么内层用 ParallelAgent：三个分析彼此独立（JD 不需要等代码），并行省时；
 *   - 为什么外层用 SequentialAgent：面试必须等分析结束拿到物料才能出题，只能串行；
 *   - 为什么合并用 ConcatenationMergeStrategy("\n\n")：把三份清单拼成一段话当统一物料，
 *     需要的是"一段完整文本"而非"谁说了什么的映射"（后者用 Default/List 策略）。
 * 嵌套规则：subAgents 参数类型是统一的 Agent——ParallelAgent 和 ReactAgent 都能往里放，
 * 这就是"编排块可以再被编排"的组合能力（路由 → 流水线 → 并行，三层串联）。
 * ─────────────────────────────────────────────────────────
 */
@Service
public class CombinedInterviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(CombinedInterviewPipeline.class);

    // 综合面试流水线：构造时 build 一次（并行分析 → 面试官），之后每次请求复用
    private final SequentialAgent pipeline;
    // 面试官
    private final InterviewAssistant interviewer;
    // 短期记忆 + checkpoint：与 CodeReviewPipeline 同一套收尾逻辑
    private final ChatMemory chatMemory;
    private final MemorySaver memorySaver;
    // 题库知识检索（阶段 4.2 重构）：RAG 前置检索收敛到 KnowledgeSearchService（Rule of Three 抽公共组件）
    private final KnowledgeSearchService knowledgeSearchService;

    public CombinedInterviewPipeline(CodeAnalyzerAgent codeAnalyzer,
                                     JdAnalyzerAgent jdAnalyzer,
                                     ResumeAnalyzerAgent resumeAnalyzer,
                                     InterviewAssistant interviewer,
                                     ChatMemory chatMemory,
                                     MemorySaver memorySaver,
                                     KnowledgeSearchService knowledgeSearchService) {

        // ── 内层：并行分析（三个分析官同时跑）──
        // mergeOutputKey：合并结果写入图状态的键（形式要件，物理传数据靠 DB + 状态消息，见类注释）
        ParallelAgent parallel = ParallelAgent.builder()
                .name("parallel-analyzers")                                  // 并行节点名（日志可见三路同时跑）
                .description("JD/简历/代码 三路并行分析，合并事实清单")         // 职责描述
                .subAgents(List.of(
                        jdAnalyzer.getAgent(),        // ① JD 分析
                        resumeAnalyzer.getAgent(),    // ② 简历分析
                        codeAnalyzer.getAgent()))     // ③ 代码分析
                .mergeStrategy(new ConcatenationMergeStrategy("\n\n"))       // 合并策略：三份输出用空行拼成一段
                .mergeOutputKey("merged_facts")                              // 合并结果放 state 的哪个键
                .maxConcurrency(3)                                           // 最多 3 个并发（默认所有子 Agent 并发）
                .build();

        // ── 外层：并行分析完 → 面试官出题（串行，面试官必须在分析之后）──
        this.pipeline = SequentialAgent.builder()
                .name("combined-interview")                                  // 综合面试节点名
                .description("综合面试流水线：JD/简历/代码并行分析 → 面试官一并出题")  // 职责描述（路由 LLM 靠它选路）
                .subAgents(List.of(parallel, interviewer.getAgent()))
                .saver(memorySaver)                                          // checkpoint 按 threadId 隔离，跑完 release
                .build();

        this.interviewer = interviewer;
        this.chatMemory = chatMemory;
        this.memorySaver = memorySaver;
        this.knowledgeSearchService = knowledgeSearchService;

        log.info("综合面试流水线构建完成：ParallelAgent(3 路分析) → Interviewer");
    }

    /**
     * 暴露内部的 SequentialAgent 给上层编排器（AgentRouter）使用。
     * 与 CodeReviewPipeline.getPipeline() 同一模式。
     */
    public SequentialAgent getPipeline() {
        return pipeline;
    }

    /**
     * 发起一次综合面试（SSE 流式）。
     *
     * 与 CodeReviewPipeline.codeReviewChat() 同套路：
     *   组装通用上下文（RAG + 窗口历史 + 用户消息）→ 跑流水线 → 过滤成文本流 → 收尾写回窗口 + 释放 checkpoint。
     *
     * @param userMessage 用户消息（需指出 JD/简历的位置或贴出文本 + 代码文件路径）
     * @param sessionId   会话 id（threadId，隔离清单/记忆/checkpoint）
     */
    public Flux<String> chat(String userMessage, String sessionId) {

        // 组装初始消息：题库知识干预不了分析官（它们读的是 JD/简历/代码），但对面试官出题有帮助
        List<Message> messages = new ArrayList<>();

        String knowledgeContext = knowledgeSearchService.search(userMessage);
        if (!knowledgeContext.isBlank()) {
            messages.add(new SystemMessage(knowledgeContext));
        }

        messages.addAll(chatMemory.get(sessionId));
        messages.add(new UserMessage(userMessage));

        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();

        log.info("综合面试流水线启动：sessionId={}，链路 = 并行分析(JD/简历/代码) → 面试官", sessionId);

        Flux<Message> agentStream;
        try {
            agentStream = pipeline.streamMessages(messages, config);
        } catch (GraphRunnerException e) {
            log.error("综合面试流水线启动失败：sessionId={}", sessionId, e);
            return Flux.error(e);
        }

        // 过滤成"助手说的话"（三个分析官的输出文字也可能混进流，属预期行为，见风险 15）
        Flux<String> answer = agentStream
                .filter(m -> m instanceof AssistantMessage)
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank());

        AtomicReference<String> fullAnswer = new AtomicReference<>("");

        // 收尾：写回短期窗口 + 释放 checkpoint（与 4.1 同一套）
        return answer
                .doOnNext(chunk -> fullAnswer.set(fullAnswer.get() + chunk))
                .doFinally(signal -> {
                    if (signal == SignalType.ON_COMPLETE) {
                        chatMemory.add(sessionId, List.of(
                                new UserMessage(userMessage),
                                new AssistantMessage(fullAnswer.get())));
                        try {
                            memorySaver.release(config);
                        } catch (Exception e) {
                            log.warn("释放 checkpoint 失败：sessionId={}, {}", sessionId, e.getMessage());
                        }
                    }
                });
    }

}