package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.xiao.aiagent.tools.InterviewTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 面试主 Agent（阶段 3.1 重构）：基于 Spring AI Alibaba 的 ReactAgent 显式推理循环
 * 「思考 → 行动(工具调用) → 观察(结果回填)」替代裸 ChatClient。
 * MemorySaver 保存 Agent 运行状态（checkpoint 按 threadId=sessionId 隔离，运行结束 release）。
 *
 * ── 如何理解这个文件（心智模型）────────────────────────────
 * 1. 把 ReactAgent 想成"一场面试循环"：它内部有
 *      「思考/行动」节点(LLM 读记录本、决定说啥或调哪个工具)
 *      「观察」节点(工具真正执行、把结果写回记录本)
 *     这两个节点交替执行，直到 LLM 输出里没有"调用工具"的标记就结束。
 * 2. 那本"记录本"就是图状态，最关键的一个键叫 messages（整场对话的消息）。
 * 3. 记录本按 threadId 区分是哪场面试（threadId = 我们的 sessionId）。
 * 4. ReactAgent 不再使用 ChatClient 的 advisor（记忆顾问/RAG顾问都没了），
 *    所以"抄历史、检索题库、存回窗口"这些原本自动的活，全部在这里手动做。
 * ─────────────────────────────────────────────────────────
 */
@Service
public class InterviewAssistant {

    private static final Logger log = LoggerFactory.getLogger(InterviewAssistant.class);

    /**
     * 系统提示（纯静态，不含运行时变量）。
     * sessionId 不进系统提示：打分落库时的会话归属由服务端通过 RunnableConfig.threadId 注入，
     * 模型无需知道会话 id。
     * 题库知识不再由 QuestionAnswerAdvisor 注入，改为 chat() 里前置检索后以 system 消息给出。
     */
    private static final String SYSTEM_PROMPT = """
            你是一位资深技术面试官，负责对求职者进行技术面试模拟。
            你的职责：
            1. 根据岗位和求职者水平出题（Java/Redis/数据库等），题目循序渐进；
            2. 针对回答进行追问，答错时给予提示引导；
            3. 点评回答并给出评分与改进建议，点评完成后必须调用 scoreRecord 工具把评分落库；
            4. 出题时优先参考知识库中的题库内容，引用时注明出处。
            5. 求职者询问答题统计或薄弱考点时，调用 questionStats 工具查询后如实转述结果。
            6. 求职者分享某个 URL（文章、技术文档等）让你参考时，使用 fetch 工具读取该网页内容，
               基于文章内容出题，弥补题库可能没有覆盖的主题。
            7. 求职者提供本地项目目录路径让你看代码时，使用文件读取工具（read_file/list_directory/search_files）
               读取其代码，针对真实实现提问（如"你这里为什么用 X 方案？有什么风险？"）。
               注意：你只能读取文件，不要修改或删除求职者的任何文件。

            评分标准（0~10 分，请严格按此打分）：
            - 回答是否准确、完整（核心得分项）；
            - 是否涉及关键知识点；
            - 是否表达清晰、条理。
            分档参考：0-3 完全不会或严重错误 / 4-6 部分正确、有缺失 / 7-9 较完整、有小瑕疵 / 10 准确全面。
            打分后必须调用 scoreRecord 工具落库，再给出点评。已点评过的同一道题不要重复调用 scoreRecord 重新落库。

            要求：语气专业、友好，全程使用中文。
            """;

    // ReactAgent：拼装好的"一次面试循环"的可运行图（构造时 build 一次，之后每次请求复用）
    private final ReactAgent agent;
    // 短期记忆：Redis 滑动窗口（20 条对话）。ReactAgent 不带记忆 advisor，
    // 所以"取历史 / 存回窗口"都靠我们在这类里手动调它（见 chat() 第 1、4 步）
    private final ChatMemory chatMemory;
    // Agent 运行状态保存器：每轮循环的"记录本快照"按 threadId 隔离。
    // 每轮跑完会 release() 释放，防止下轮把上一轮整本记录（旧消息）重复带进上下文
    private final MemorySaver memorySaver;
    // 题库向量库（自动装配的 ai-agent-index）：供 searchKnowledge() 做前置 RAG 检索用
    private final VectorStore knowledgeStore;

    public InterviewAssistant(ChatModel chatModel,
                              InterviewTools interviewTools,
                              ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
                              VectorStore vectorStore,
                              ChatMemory chatMemory,
                              MemorySaver memorySaver) {

        // MCP 工具通过 ObjectProvider 注入（而非直接 @Autowired）：
        // 原因是降级兼容——当未配置 MCP server 或 npx 拉包失败时，自动装配不会产出
        // ToolCallbackProvider bean，此时 getIfAvailable() 返回 null，Agent 只用现有
        // @Tool 工具，核心面试功能不受影响；配好 MCP 时才把外部工具也注册进去。
        ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
        if (mcpProvider != null) {
            log.info("MCP 工具已注入 ReactAgent，可用工具数：{}", mcpProvider.getToolCallbacks().length);
        } else {
            log.warn("未检测到 MCP 工具（未配置 server 或 npx 拉包失败），ReactAgent 仅使用内置 @Tool 工具。");
        }

        // ── 拼装这场"面试循环"：每行都是在回答"这个 agent 要什么" ──
        // 整段 = 面试官 + 大脑 + 武器 + 存档器 四件套，build() 后生成一张可运行的图。
        Builder builder = ReactAgent.builder()
                .name("interviewer")          // 给这 agent 起名，图日志/调试里显示用
                .description("AI 智能面试官：出题、追问、点评打分")  // 描述它负责干什么
                .systemPrompt(SYSTEM_PROMPT)  // 【大脑人设】面试官身份 + 全部行为规则（出题/追问/评分/落库）
                .model(chatModel)             // 【大脑本体】用哪个大模型：qwen（取代了 ChatClient.Builder）
                .methodTools(interviewTools)  // 【武器】允许调用的内置 @Tool 工具（scoreRecord/questionStats/calculator）
                .saver(memorySaver)           // 【存档器】每轮循环的"记录本快照"存到哪、按 threadId 分会话
                .enableLogging(true);         // 打开各节点(思考/观察)请求响应日志，方便看循环过程（可观测性）

        if (mcpProvider != null) {
            builder.toolCallbackProviders(mcpProvider);   // MCP 外部工具（Filesystem 只读）与内置工具并存
        }

        this.agent = builder.build();
        this.chatMemory = chatMemory;
        this.memorySaver = memorySaver;
        this.knowledgeStore = vectorStore;
    }

    /**
     * 发送一次面试对话请求（SSE 流式）。
     *
     * 流程：题库 RAG 前置检索 + Redis 窗口历史组装消息 → ReactAgent 流式推理
     * → 完成后写回短期窗口并释放本次运行的 checkpoint。
     *
     * sessionId 由前端（或 API 调用方）传入，后端不生成。它被用于两处：
     *  1. threadId → ReAct checkpoint 的会话隔离键；
     *  2. 工具内从框架注入的 RunnableConfig 取回 threadId 作为评分归属的 sessionId。
     */
    public Flux<String> chat(String userMessage, String sessionId) {

        // ── 第 1 步：给"这场面试"准备好开场记录本（图状态 messages 的初始内容）──
        // 这里在模拟以前记忆顾问 + RAG 顾问自动做的事，只是现在由我们显式组装：
        //   ① 长期/题库知识（searchKnowledge 检索结果）→ 写成一条 system 消息；
        //   ② 短期窗口历史（chatMemory.get）→ 最近 20 条对话抄进来；
        //   ③ 最后放上面试者这句新提问。
        List<Message> messages = new ArrayList<>();

        String knowledgeContext = searchKnowledge(userMessage);
        if (!knowledgeContext.isBlank()) {
            messages.add(new SystemMessage(knowledgeContext));
        }

        messages.addAll(chatMemory.get(sessionId));          // Redis 滑动窗口历史
        messages.add(new UserMessage(userMessage));

        // ── 第 2 步：指定"这是哪场面试"并开跑循环 ──
        // threadId = sessionId：既用来隔离每本书(会话)，也是工具里取 sessionId 的来源。
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();

        Flux<Message> agentStream;
        try {
            // 把开场记录本塞进图，开始"思考→行动→观察"循环，流式吐出一段段消息。
            // streamMessages 会抛受检异常 GraphRunnerException，所以用 try/catch 包住。
            agentStream = agent.streamMessages(messages, config);
        } catch (GraphRunnerException e) {
            log.error("ReAct 循环启动失败：sessionId={}", sessionId, e);
            return Flux.error(e);
        }

        // ── 第 3 步：把图吐出的消息流，过滤成"助手说的文字"给前端（SSE 打字机）──
        // 图会吐出多种消息（可能含工具调用的中间消息），只要 AssistantMessage 的正文文本。
        Flux<String> answer = agentStream
                .filter(m -> m instanceof AssistantMessage)
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank());

        AtomicReference<String> fullAnswer = new AtomicReference<>("");

        // ── 第 4 步：返回流 + 收尾 ──
        // Flux.concat：先在前面拼一个"💭 正在思考…"的状态提示，再接回答正文。
        return Flux.concat(Flux.just("💭 正在思考…\n"), answer)
                .doOnNext(chunk -> fullAnswer.set(fullAnswer.get() + chunk))  // 边流边累加出完整回答
                .doFinally(signal -> {                                       // 流结束后统一收尾
                    if (signal == SignalType.ON_COMPLETE) {                  // 只有"正常跑完"才算数
                        // 4a. 把"面试者的话 + 完整回答"写回 Redis 短期窗口（MessageWindowChatMemory 自动淘汰最旧）。
                        //     由于没有记忆 advisor 了，这一步必须手写，否则下轮短期记忆失效。
                        chatMemory.add(sessionId, List.of(
                                new UserMessage(userMessage),
                                new AssistantMessage(fullAnswer.get())));
                        // 4b. release 释放本轮"记录本快照"。
                        //     我们把历史用 Redis 窗口自己管了，若让图把整本(含旧消息)跨轮保留，
                        //     下轮会和窗口历史重复；所以跑完即释放。
                        try {
                            memorySaver.release(config);
                        } catch (Exception e) {
                            log.warn("释放 checkpoint 失败：sessionId={}, {}", sessionId, e.getMessage());
                        }
                    }
                });
    }

    /** 题库 RAG 前置检索：只基于用户本轮消息检索一次，结果注入为 system 消息（替代 QuestionAnswerAdvisor） */
    private String searchKnowledge(String userMessage) {
        List<Document> hits = knowledgeStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessage)
                        .topK(3)
                        .similarityThreshold(0.7)
                        .build());
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【题库知识】以下内容来自面试题库，出题时请优先参考：\n");
        for (Document d : hits) {
            sb.append("- ").append(d.getText()).append("\n");
        }
        return sb.toString();
    }

}
