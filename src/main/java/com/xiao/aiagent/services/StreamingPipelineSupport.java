package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式流水线基类（阶段 4.2 课后重构，第三次 Rule of Three 触发点）。
 *
 * 背景：AgentRouter / CodeReviewPipeline / CombinedInterviewPipeline 三个编排类里，
 * 都有一份结构相同的模板代码（约 30 行 × 3 处）：
 *   组装"通用上下文" → 开跑编排图 → 过滤成助手消息 → 累加完整回答 → 收尾
 *   （写回短期窗口 + 释放 checkpoint）。
 * 前两轮 Rule of Three（ProfileAnalyzerAgent / KnowledgeSearchService）已消掉部分重复，
 * 这一轮把"组装上下文 + 过滤收尾"收进本基类，三个子类各自只保留
 * 「自己的编排 Agent + 自己的快路径/复用路径 + 自己的 streamMessages 调用与启动失败日志」。
 *
 * 基类提供：
 *   1. buildChatMessages(userMessage, sessionId)：组装"通用上下文"
 *      （题库知识 RAG → SystemMessage + 窗口历史 + 用户消息）；
 *   2. streamAssistantAnswers(agentStream, userMessage, config)：过滤 + 累加 + 收尾；
 *   3. 持有 chatMemory / memorySaver / knowledgeSearchService 三个公共依赖。
 *
 * 为什么不把"开跑"（streamMessages）也收进来？
 *   三个子类各自持有不同的编排 Agent（router / pipeline），且各自想打不同的启动
 *   失败日志；如果为统一把"开跑"做成函数式接口/Lambda 参数，方法签名会变得抽象难懂——
 *   消掉重复的目的是让人更容易懂，若引入的间接层比重复还难懂就不该消。
 *   判断标准（DRY 的边界）：能一眼看懂的重复消掉；消掉后引入的间接层比重复还难懂就不消。
 *
 * 注意：本类是抽象基类，不标 @Service（自身不该被注册成 Bean），子类标 @Service 即可。
 */
public abstract class StreamingPipelineSupport {

    // 日志用运行时类名（与 ProfileAnalyzerAgent 同技法）：子类打日志显示各自的类名
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ChatMemory chatMemory;
    protected final MemorySaver memorySaver;
    protected final KnowledgeSearchService knowledgeSearchService;

    protected StreamingPipelineSupport(ChatMemory chatMemory,
                                       MemorySaver memorySaver,
                                       KnowledgeSearchService knowledgeSearchService) {
        this.chatMemory = chatMemory;
        this.memorySaver = memorySaver;
        this.knowledgeSearchService = knowledgeSearchService;
    }

    /**
     * 组装"通用上下文"（入口只有一次机会——因为路由/并行发生在图内部，没法先路由再定制）：
     *   ① 题库知识（RAG 前置检索，KnowledgeSearchService）→ SystemMessage（检索不到跳过）；
     *   ② 短期窗口历史 → 平铺进消息列表（最近 maxMessages 条）；
     *   ③ 用户本轮消息。
     */
    protected List<Message> buildChatMessages(String userMessage, String sessionId) {
        List<Message> messages = new ArrayList<>();

        String knowledgeContext = knowledgeSearchService.search(userMessage);
        if (!knowledgeContext.isBlank()) {
            messages.add(new SystemMessage(knowledgeContext));
        }

        messages.addAll(chatMemory.get(sessionId));
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    /**
     * 把编排图吐出的消息流过滤成"助手说的话"，并绑定收尾逻辑。
     *
     * 过滤：图会混入工具调用、节点状态等中间消息，只保留 AssistantMessage 的正文文本；
     * 累加：边流边在 fullAnswer 里攒出完整回答（AtomicReference：被闭包异步共享的可变引用）；
     * 收尾：只有【正常跑完】才写回短期窗口 + 释放 checkpoint——出错/取消不写回，
     *      避免把半截回答记进短期记忆。
     *
     * @param agentStream 编排图吐出的消息流
     * @param userMessage 用户本轮消息（收尾时和完整回答一起写回短期窗口）
     * @param config      本次运行的 RunnableConfig（threadId=sessionId，释放 checkpoint 用同一个）
     */
    protected Flux<String> streamAssistantAnswers(Flux<Message> agentStream,
                                                  String userMessage,
                                                  RunnableConfig config) {
        String sessionId = config.threadId().orElse("unknown");

        Flux<String> answer = agentStream
                .filter(m -> m instanceof AssistantMessage)
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank());

        AtomicReference<String> fullAnswer = new AtomicReference<>("");

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