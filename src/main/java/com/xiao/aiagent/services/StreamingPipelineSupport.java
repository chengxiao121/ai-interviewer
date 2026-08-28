package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.xiao.aiagent.entity.InterviewPlan;
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
    /** 跨会话薄弱点回顾（4.3 新增）：开场注入候选人的历史薄弱点摘要 */
    protected final WeaknessProfileService weaknessProfileService;
    /** 面试计划（阶段 5.2 新增）："考什么"的范围契约，buildChatMessages 每轮注入 */
    protected final InterviewPlanService interviewPlanService;
    /** 答案评估（阶段 5.3 新增）：会话内评估闭环，buildChatMessages 每轮先评估再注入 */
    protected final AnswerEvaluatorService answerEvaluatorService;

    protected StreamingPipelineSupport(ChatMemory chatMemory,
                                       MemorySaver memorySaver,
                                       KnowledgeSearchService knowledgeSearchService,
                                       WeaknessProfileService weaknessProfileService,
                                       InterviewPlanService interviewPlanService,
                                       AnswerEvaluatorService answerEvaluatorService) {
        this.chatMemory = chatMemory;
        this.memorySaver = memorySaver;
        this.knowledgeSearchService = knowledgeSearchService;
        this.weaknessProfileService = weaknessProfileService;
        this.interviewPlanService = interviewPlanService;
        this.answerEvaluatorService = answerEvaluatorService;
    }

    /**
     * 组装"通用上下文"（入口只有一次机会——因为路由/并行发生在图内部，没法先路由再定制）：
     *   ① 跨会话薄弱点回顾（4.3，WeaknessProfileService）→ SystemMessage（无历史记录跳过）；
     *   ② 题库知识（RAG 前置检索，KnowledgeSearchService）→ SystemMessage（检索不到跳过）；
     *   ③ 短期窗口历史 → 平铺进消息列表（最近 maxMessages 条）；
     *   ④ 用户本轮消息。
     */
    protected List<Message> buildChatMessages(String userMessage, String sessionId, String candidateId) {
        List<Message> messages = new ArrayList<>();

        // ① 跨会话薄弱点回顾（4.3）：候选人有历史评分才注入，让面试官开场即"记得"上次的薄弱考点；
        //    无评分记录时 loadProfile 返回空串，跳过注入，不污染新候选人/无历史的开场
        String weaknessContext = weaknessProfileService.loadProfile(candidateId);
        if (!weaknessContext.isBlank()) {
            messages.add(new SystemMessage(weaknessContext));
        }

        // ①b 面试计划（阶段 5.2）：无计划则生成（幂等，已有计划直接返回），注入后面试官按计划考点出题。
        //    生成失败返回 null → 渲染为空串 → 跳过注入，面试不阻塞（计划是增强项不是阻塞项）
        InterviewPlan plan = interviewPlanService.ensurePlan(userMessage, sessionId, candidateId);
        String planContext = interviewPlanService.renderPlan(plan);
        if (!planContext.isBlank()) {
            messages.add(new SystemMessage(planContext));
        }

        // ② 题库知识
        String knowledgeContext = knowledgeSearchService.search(userMessage);
        if (!knowledgeContext.isBlank()) {
            messages.add(new SystemMessage(knowledgeContext));
        }

        // ②b 会话内评估（阶段 5.3 验收修复）：评估"上一问 + 本轮消息"。
        // 为什么挂在这条公共装配点上：面试官在两条编排路径（语义路由 / 资料流水线）里都是
        // 作为【子图】运行的，不会流经 InterviewAssistant.chat() 里的评估步骤——
        // 验收实测踩中：普通面试（无资料会话）每轮都走语义路由，评估从未触发、评分从未落库。
        // 作答 → 代码侧落库 score_record + 注入【上一题评估】；非作答/失败 → 空注入不影响流程。
        // 与 InterviewAssistant.chat() 的评估不重复：chat() 只在直连路径（复用快路径）被调用，
        // 那条路径不经本方法，二者互斥覆盖全部入口。
        AnswerEvaluatorService.EvaluationOutcome evaluation =
                answerEvaluatorService.evaluateTurn(userMessage, sessionId, candidateId, plan);
        if (!evaluation.injectionText().isBlank()) {
            messages.add(new SystemMessage(evaluation.injectionText()));
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