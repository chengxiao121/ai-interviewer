package com.xiao.aiagent.agent;
import com.xiao.aiagent.service.InterviewPlanService;
import com.xiao.aiagent.service.InterviewLifecycleService;
import com.xiao.aiagent.common.OutputGuard;
import com.xiao.aiagent.service.KnowledgeSearchService;
import com.xiao.aiagent.service.AnswerEvaluatorService;
import com.xiao.aiagent.service.WeaknessProfileService;

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
 * 这一轮把"组装上下文 + 过滤收尾"收进本基类；P2-7 收口后 InterviewAssistant（直连快路径）
 * 也加入继承——四条入口路径共用同一份装配与收尾，子类各自只保留
 * 「自己的编排 Agent + 自己的快路径/复用路径 + 自己的 streamMessages 调用与启动失败日志」，
 * 自有差异项（如直连路径的代码事实清单前置注入）经 extraContext 钩子保留。
 *
 * 基类提供：
 *   1. buildChatMessages(userMessage, sessionId)：组装"通用上下文"
 *      （薄弱点回顾 / 会话状态 / 面试计划 / 扩展点 / 会话内评估 → SystemMessage
 *       + 窗口历史 + 用户消息）；
 *   2. streamAssistantAnswers(agentStream, userMessage, config)：过滤 + 累加 + 收尾；
 *   3. 持有 chatMemory / memorySaver / knowledgeSearchService 等公共依赖。
 *
 * 阶段 12 变更（决策 6）：buildChatMessages 不再注入题库 RAG 检索结果。
 *   原因：资料模式的出题素材已有更可靠的来源（JD 画像、简历/代码物料、面试计划），
 *   而拿候选人的【答案】当检索词去捞下一题素材属于检索错配——答"Redis 持久化"，
 *   捞回来的还是持久化内容，题目被钉死在同一处，噪音大于信号。
 *   知识库模式（阶段 3~5）改走"入场组卷"，面试中同样不做 RAG。
 *   KnowledgeSearchService 自此无调用方，代码保留至阶段 7 再决定删除或接回。
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
    /**
     * 题库向量检索（阶段 12 起已无调用方，待阶段 7 清理）。
     * 仅因构造函数签名被三个子类共用而保留持有，不删除以免牵动 AgentRouter /
     * InterviewAssistant / MaterialInterviewPipeline 的 super(...) 调用。
     */
    protected final KnowledgeSearchService knowledgeSearchService;
    /** 跨会话薄弱点回顾（4.3 新增）：开场注入候选人的历史薄弱点摘要 */
    protected final WeaknessProfileService weaknessProfileService;
    /** 面试计划（阶段 5.2 新增）："考什么"的范围契约，buildChatMessages 每轮注入 */
    protected final InterviewPlanService interviewPlanService;
    /** 答案评估（阶段 5.3 新增）：会话内评估闭环，buildChatMessages 每轮先评估再注入 */
    protected final AnswerEvaluatorService answerEvaluatorService;
    /** 会话生命周期（阶段 11 新增）：已结束的面试不再出题，buildChatMessages 每轮查状态分叉 */
    protected final InterviewLifecycleService interviewLifecycleService;
    /** 输出守卫模式（阶段 4 新增）：子类经 @Value 读配置后透传，streamAssistantAnswers 出口兜底用 */
    protected final OutputGuard.Mode outputGuardMode;

    /** 面试已结束后的行为约束提示（阶段 11）：每轮注入，压住"按计划继续出题"的惯性 */
    static final String FINISHED_PROMPT =
            "【会话状态】本场面试已经结束。你不再是面试官：绝对不要提出任何新问题，"
                    + "不要按面试计划推进考点，不要对候选人的发言打分。"
                    + "只根据候选人此刻的提问做答疑、复盘与总结；"
                    + "如果候选人没有提问，简短说明面试已结束、可查看评估报告。";

    protected StreamingPipelineSupport(ChatMemory chatMemory,
                                       MemorySaver memorySaver,
                                       KnowledgeSearchService knowledgeSearchService,
                                       WeaknessProfileService weaknessProfileService,
                                       InterviewPlanService interviewPlanService,
                                       AnswerEvaluatorService answerEvaluatorService,
                                       InterviewLifecycleService interviewLifecycleService,
                                       OutputGuard.Mode outputGuardMode) {
        this.chatMemory = chatMemory;
        this.memorySaver = memorySaver;
        this.knowledgeSearchService = knowledgeSearchService;
        this.weaknessProfileService = weaknessProfileService;
        this.interviewPlanService = interviewPlanService;
        this.answerEvaluatorService = answerEvaluatorService;
        this.interviewLifecycleService = interviewLifecycleService;
        this.outputGuardMode = outputGuardMode;
    }

    /**
     * 组装"通用上下文"（入口只有一次机会——因为路由/并行发生在图内部，没法先路由再定制）：
     *   ① 跨会话薄弱点回顾（4.3，WeaknessProfileService）→ SystemMessage（无历史记录跳过）；
     *   ② 会话状态分叉（阶段 11）：
     *      - 已结束 → 注入 FINISHED_PROMPT（不出题不评分），跳过计划与评估（它们的职责
     *        是"驱动面试"，结束后继续跑只会白生成计划、误判作答）；
     *      - 进行中 → 面试计划（5.2）+ 会话内评估（5.3），原行为不变；
     *   ③ 扩展点钩子（子类自有上下文，如直连路径的代码事实清单）；
     *   ④ 短期窗口历史 → 平铺进消息列表（最近 maxMessages 条）；
     *   ⑤ 用户本轮消息。
     *
     * 已移除项（阶段 12）：原第 ③ 步"题库知识 RAG 检索"不再注入——检索 query 拿的是
     * 候选人的答案，与"下一题该问什么"错配，详见类注释"阶段 12 变更"。
     */
    protected List<Message> buildChatMessages(String userMessage, String sessionId, String candidateId) {
        List<Message> messages = new ArrayList<>();

        // ① 跨会话薄弱点回顾（4.3）：候选人有历史评分才注入，让面试官开场即"记得"上次的薄弱考点；
        //    无评分记录时 loadProfile 返回空串，跳过注入，不污染新候选人/无历史的开场
        String weaknessContext = weaknessProfileService.loadProfile(candidateId);
        if (!weaknessContext.isBlank()) {
            messages.add(new SystemMessage(weaknessContext));
        }

        // ② 会话状态分叉（阶段 11）：状态查询一轮一次（本地 PG，代价可忽略），
        //    换来"结束后绝不出题"的确定性——模型自觉靠 prompt，状态机靠这里
        boolean finished = interviewLifecycleService.isFinished(sessionId);
        InterviewPlan plan = null;
        if (finished) {
            messages.add(new SystemMessage(FINISHED_PROMPT));
        } else {
            // 面试计划（阶段 5.2）：无计划则生成（幂等，已有计划直接返回），注入后面试官按计划考点出题。
            // 生成失败返回 null → 渲染为空串 → 跳过注入，面试不阻塞（计划是增强项不是阻塞项）
            plan = interviewPlanService.ensurePlan(userMessage, sessionId, candidateId);
            String planContext = interviewPlanService.renderPlan(plan);
            if (!planContext.isBlank()) {
                messages.add(new SystemMessage(planContext));
            }
        }

        // ①c 扩展点钩子（P2-7 收口第二步新增）：子类有基类未覆盖的上下文时覆写 extraContext。
        //    默认空串 = 不注入，三条编排路径（路由/资料/综合）行为不变；
        //    InterviewAssistant（直连快路径）用它在原位置前置注入代码事实清单——
        //    若直接裸复用而不留钩子，直连路径会丢掉"清单保证注入"，退化成指望模型自觉调工具。
        String extraContext = extraContext(userMessage, sessionId);
        if (!extraContext.isBlank()) {
            messages.add(new SystemMessage(extraContext));
        }

        // ②b 会话内评估（阶段 5.3 验收修复）：评估"上一问 + 本轮消息"。仅进行中的面试执行——
        // 已结束的会话不再产生新作答，评估（含落库）跳过（见上方状态分叉注释）。
        // 为什么挂在这条公共装配点上：面试官在两条编排路径（语义路由 / 资料流水线）里都是
        // 作为【子图】运行的，不会流经 InterviewAssistant.chat() 里的评估步骤——
        // 验收实测踩中：普通面试（无资料会话）每轮都走语义路由，评估从未触发、评分从未落库。
        // 作答 → 代码侧落库 score_record + 注入【上一题评估】；非作答/失败 → 空注入不影响流程。
        // 与 InterviewAssistant.chat() 的评估不重复：chat() 只在直连路径（复用快路径）被调用，
        // 那条路径不经本方法，二者互斥覆盖全部入口。
        if (!finished) {
            AnswerEvaluatorService.EvaluationOutcome evaluation =
                    answerEvaluatorService.evaluateTurn(userMessage, sessionId, candidateId, plan);
            if (!evaluation.injectionText().isBlank()) {
                messages.add(new SystemMessage(evaluation.injectionText()));
            }
        }

        messages.addAll(chatMemory.get(sessionId));
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    /**
     * 上下文装配扩展点（P2-7 收口新增）：子类需要注入基类未覆盖的上下文时覆写，默认空串不注入。
     * 钩子位置：薄弱点回顾/面试计划之后、会话内评估之前——与收口前 InterviewAssistant.chat()
     * 手写装配的注入顺序一致（薄弱点 → 计划 → 代码清单 → 评估），收口不改顺序。
     * （原顺序里的"RAG"一步已于阶段 12 移除，见类注释。）
     */
    protected String extraContext(String userMessage, String sessionId) {
        return "";
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

        // ── 输出守卫（阶段 4）：只作用于"转发给前端"这一环 ──
        // rawAnswer 的完整累加（doOnNext 那行）在守卫之前、与转发解耦——无论 log/truncate，
        // 短期记忆写回的都是完整原文，被截断的半截回答绝不进记忆（红线）。
        // log 模式：feed 只计数不干预流；truncate 模式：命中后返回 null 停止转发；
        // off 模式：guard 对象仍 new 出来但 feed 直通（避免分支判断，统一走同一管道）。
        OutputGuard guard = new OutputGuard(outputGuardMode);

        return answer
                .doOnNext(chunk -> fullAnswer.set(fullAnswer.get() + chunk))
                .map(chunk -> guard.feed(chunk))
                .filter(t -> t != null && !t.isEmpty())
                .doFinally(signal -> {
                    if (signal == SignalType.ON_COMPLETE) {
                        if (guard.hitCount() > 0) {
                            log.warn("输出守卫命中敏感结构 {} 次：sessionId={}, mode={}",
                                    guard.hitCount(), sessionId, outputGuardMode);
                        }
                        if (fullAnswer.get().isBlank()) {
                            // 流层过滤（阶段6）白名单过严或模型无输出时，前端会静默空白——
                            // 留一条可查的日志线索（行为不变：照常写回记忆并释放 checkpoint）
                            log.warn("本轮面试官输出为空（流层过滤全拦/模型无输出）：sessionId={}", sessionId);
                        }
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