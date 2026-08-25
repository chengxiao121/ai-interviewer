package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.xiao.aiagent.repository.CodeProfileRepository;
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
import java.util.regex.Pattern;

/**
 * 代码评审面试流水线（阶段 4.1 新增）——双 Agent 编排器。
 *
 * 用 SequentialAgent 把两个 ReactAgent 串成顺序流水线：
 *   ① CodeAnalyzerAgent（分析型）：MCP 读代码 → 产出事实清单 → saveCodeProfile 落库
 *   ② InterviewAssistant（对话型）：getCodeFacts 取清单 → 基于清单出题追问
 *
 * ── 编排器心智模型 ────────────────────────────────────────
 * SequentialAgent 就像"接力赛的组织者"：它只保证第一棒跑完再跑第二棒，
 * 不负责把接力棒递过去——接力棒（事实清单）走的是数据库（code_profile 表）。
 * 这就是"编排"与"数据传递"解耦：编排管时序，数据契约管内容。
 * ───────────────────────────────────────────────────────
 *
 * 复用路径（"落库而非内存传递"的红利）：
 *   同 sessionId 已分析过（code_profile 有记录）→ 跳过分析 Agent，直接调面试官，
 *   省一次 LLM 分析调用。这就是第 1 步设计决策在这里的兑现。
 */
@Service
public class CodeReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewPipeline.class);

    // 双 Agent 流水线：构造时 build 一次，之后每次请求复用
    private final SequentialAgent pipeline;
    // 面试官（复用路径直接调它的 chat()）
    private final InterviewAssistant interviewer;
    // 判断"该会话分析过没"——决定走流水线还是复用路径
    private final CodeProfileRepository codeProfileRepository;
    // 短期记忆 + checkpoint：与 InterviewAssistant.chat() 同一套收尾逻辑
    private final ChatMemory chatMemory;
    private final MemorySaver memorySaver;
    // 题库知识检索（阶段 4.2 重构）：RAG 前置检索收敛到 KnowledgeSearchService（Rule of Three 抽公共组件）
    private final KnowledgeSearchService knowledgeSearchService;

    public CodeReviewPipeline(CodeAnalyzerAgent codeAnalyzer,
                              InterviewAssistant interviewer,
                              CodeProfileRepository codeProfileRepository,
                              ChatMemory chatMemory,
                              MemorySaver memorySaver,
                              KnowledgeSearchService knowledgeSearchService) {

        // ── 组装双 Agent 流水线 ──
        // subAgents 里的顺序 = 执行顺序：先分析、后面试。
        // 两个子 Agent 通过 getAgent() 拿到（Service 包装透出 ReactAgent 的模式，见各自类注释）。
        this.pipeline = SequentialAgent.builder()
                .name("code-review-pipeline")                                    // 编排器名字（日志里可见）
                .description("代码评审面试流水线：代码分析 → 面试官出题")            // 职责描述
                .subAgents(List.of(codeAnalyzer.getAgent(), interviewer.getAgent()))  // 【顺序编排】分析在前、面试在后
                .saver(memorySaver)                                              // checkpoint 按 threadId 隔离，跑完 release
                .build();

        this.interviewer = interviewer;
        this.codeProfileRepository = codeProfileRepository;
        this.chatMemory = chatMemory;
        this.memorySaver = memorySaver;
        this.knowledgeSearchService = knowledgeSearchService;
    }

    /**
     * 暴露内部的 SequentialAgent 给上层编排器（阶段 4.2 新增）。
     *
     * 为什么 4.1 不需要、4.2 需要？
     *   4.1 时本类就是最外层入口，Controller 直接调 codeReviewChat()；
     *   4.2 起本类降级为 AgentRouter（LlmRoutingAgent）的一个【子 Agent】，
     *   LlmRoutingAgent.builder().subAgents(...) 要求传 Agent 类型实例，
     *   所以要把拼好的流水线图透出去。
     * 与 InterviewAssistant.getAgent() / CodeAnalyzerAgent.getAgent() 同一模式：
     *   Service 包装管理生命周期和业务逻辑，需要被编排时透出 Agent。
     */
    public SequentialAgent getPipeline() {
        return pipeline;
    }

    /**
     * 发起一次代码评审面试（SSE 流式）。
     *
     * 两条路径：
     *   A. 复用路径：该会话已分析过（code_profile 有记录）→ 直接调面试官 chat()，
     *      面试官 loadCodeFacts 前置注入清单，省一次分析；
     *   B. 流水线路径：首次分析 → 跑 SequentialAgent（分析 Agent 落库 → 面试官取清单出题）。
     *
     * @param userMessage 用户消息（含文件路径）
     * @param sessionId   会话 id（threadId，隔离分析结果/记忆/checkpoint）
     */
    public Flux<String> codeReviewChat(String userMessage, String sessionId) {

        // ── 路径 A：复用路径（"落库而非内存传递"的红利）──
        // 同会话已分析过 → 跳过分析 Agent，面试官直接从库里取清单出题。
        if (codeProfileRepository.existsBySessionId(sessionId)) {
            log.info("会话 {} 已有代码分析记录，跳过分析 Agent，直接进入面试官 Agent（复用清单）", sessionId);
            return interviewer.chat(userMessage, sessionId);
        }

        // ── 路径 B：流水线路径（首次分析）──
        // 组装初始消息（与 InterviewAssistant.chat() 同套路）：
        //   ① RAG 题库知识 → system 消息（面试官用得上；分析 Agent 不受影响，它专注读代码）
        //   ② 短期窗口历史 → 面试官要的上下文
        //   ③ 用户消息（含文件路径，分析 Agent 从中提取路径调 read_file）
        List<Message> messages = new ArrayList<>();

        String knowledgeContext = knowledgeSearchService.search(userMessage);
        if (!knowledgeContext.isBlank()) {
            messages.add(new SystemMessage(knowledgeContext));
        }

        messages.addAll(chatMemory.get(sessionId));
        messages.add(new UserMessage(userMessage));

        // threadId = sessionId：子 Agent 的工具（saveCodeProfile/getCodeFacts/scoreRecord）
        // 都从框架注入的 RunnableConfig 取它做会话隔离，整条流水线共用同一把钥匙
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();

        log.info("代码评审双 Agent 流水线启动：sessionId={}，链路 = 代码分析 Agent → 面试官 Agent", sessionId);

        Flux<Message> agentStream;
        try {
            // 开跑流水线：先分析 Agent（读代码→落库），再面试官 Agent（取清单→出题）
            agentStream = pipeline.streamMessages(messages, config);
        } catch (GraphRunnerException e) {
            log.error("双 Agent 流水线启动失败：sessionId={}", sessionId, e);
            return Flux.error(e);
        }

        // 把流水线吐出的消息流过滤成"助手说的文字"给前端。
        // 注意：流水线会吐出两个 Agent 的消息（含分析 Agent 的输出），
        // 分析 Agent 的 prompt 已要求"只调工具、少输出文字"，流式形态在第 6 步验收时实测确认。
        Flux<String> answer = agentStream
                .filter(m -> m instanceof AssistantMessage)
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank());

        AtomicReference<String> fullAnswer = new AtomicReference<>("");

        // 收尾：与 InterviewAssistant.chat() 同一套——写回短期窗口 + 释放 checkpoint
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

    /** 匹配文件扩展名（判断消息里是否提到代码文件） */
    private static final Pattern FILE_EXT = Pattern.compile(
            "\\.(java|py|go|js|ts|jsx|tsx|c|cpp|h|kt|rb|php|sql|html|css|yml|yaml|xml|sh|md)\\b",
            Pattern.CASE_INSENSITIVE);
    /** 匹配 Windows 盘符路径（如 D:\... 或 D:/...） */
    private static final Pattern WIN_PATH = Pattern.compile("[A-Za-z]:[\\\\/]");
    /** 代码评审意图关键词（用户明说要看代码/评审实现时命中） */
    private static final List<String> CODE_REVIEW_KEYWORDS = List.of(
            "代码评审", "评审我的", "看我的代码", "看下我的代码", "我的实现", "针对我的", "看我代码", "这段代码");

    /**
     * 判断用户消息是否属于「代码评审面试」意图（阶段 4.1 简单规则版）。
     *
     * 为什么放在这里而不是 Controller？
     *   "判断该不该走流水线"是流水线自己的入口条件，和流水线内聚；
     *   Controller 只做分发，不装判断逻辑。4.2 要升级成 LlmRoutingAgent 时，
     *   只需替换这里的实现，Controller 不用动。
     *
     * 4.1 用简单规则（正则/关键词），零成本、确定性；4.2 再升级 LLM 语义路由。
     * 规则命中三种情况之一即算代码评审意图：
     *   ① 消息里带文件扩展名（.java/.py/...）——用户提到具体代码文件；
     *   ② 消息里带盘符路径（D:\...）——用户给了文件位置；
     *   ③ 消息含代码评审关键词——用户明说要看代码。
     */
    public static boolean isCodeReviewRequest(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        if (WIN_PATH.matcher(userMessage).find()) {
            return true;
        }
        if (FILE_EXT.matcher(userMessage).find()) {
            return true;
        }
        for (String keyword : CODE_REVIEW_KEYWORDS) {
            if (userMessage.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

}
