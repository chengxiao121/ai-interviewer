package com.xiao.aiagent.common;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

/**
 * 面试官语音流过滤器（输出纪律优化 阶段6，方案 b：流层过滤）。
 *
 * 解决的问题：编排图（分析官 → 面试官）的流式输出里混着分析官的机器产物
 * （整段 factsJson，含 verifyQuestion 明文）——MaterialSanitizer 只拦了
 * 注入路径（loadCodeFacts），拦不住展示路径，候选人在前端直接看到泄题材料。
 *
 * 做法：把「编排图消息流 → 前端/记忆」的转发口径从框架默认的
 * 「所有 Agent 的模型输出」收窄为「只有面试官说的话」：
 *   ① 类型门槛与框架 Agent.streamMessages 的内部抽取保持一致
 *      （AGENT_MODEL_STREAMING + AGENT_TOOL_FINISHED，见其 extractMessages 实现）；
 *   ② 身份门槛是本类新增的白名单：agent() 以 "interviewer" 结尾。
 *
 * 身份字段的实测语义（AgentStreamFilterProbeTest 探针，框架 1.1.2.0）：
 *   - 嵌套子图的事件 agent() = "subgraph_" + 子 Agent 名，
 *     如 subgraph_resume-analyzer / subgraph_interviewer；
 *   - 多层嵌套（路由图 → 资料流水线 → 面试官）只会叠加前缀，后缀仍是子 Agent 名，
 *     所以 endsWith 比 equals 更稳——任何入口、任何嵌套深度下面试官都能命中；
 *   - 分析官（*-analyzer）、路由器（intent-router）都不以 interviewer 结尾，天然被拦。
 *
 * 为什么选白名单而不是黑名单：白名单漏放 = 界面空白（显性故障，冒烟立刻能发现）；
 * 黑名单漏拦 = 泄题（安全问题，且新增 Agent 时容易忘记登记）。安全优先于可用性。
 *
 * 过滤位置在 streamAssistantAnswers 之前：转发给前端的与累加写回短期记忆的
 * 是同一个（已过滤的）流——前端干净与记忆干净一次到位，评估官取「上一问」
 * 也不再被分析官 JSON 污染。
 *
 * 包私有纯函数：不依赖 Spring 上下文，同包直测（项目测试约定）。
 */
public final class InterviewerStreamFilter {

    private InterviewerStreamFilter() {
    }

    /**
     * 把编排图的 NodeOutput 流收窄成「面试官说的话」消息流。
     *
     * @param stream 编排图 stream() 的原始输出（含所有子 Agent 的事件）
     * @return 只有面试官模型输出的 Flux<Message>，语义与 Agent.streamMessages 对齐
     */
    public static Flux<Message> interviewerOnly(Flux<NodeOutput> stream) {
        return stream
                .ofType(StreamingOutput.class)
                .filter(so -> isForwardableType(so.getOutputType()))
                .filter(so -> isInterviewer(so.agent()))
                .map(StreamingOutput::message);
    }

    /** 与框架 extractMessages 相同的类型门槛：模型流式块 + 工具完成事件。 */
    private static boolean isForwardableType(OutputType type) {
        return type == OutputType.AGENT_MODEL_STREAMING || type == OutputType.AGENT_TOOL_FINISHED;
    }

    /** 身份白名单：agent() 以 interviewer 结尾（subgraph_ 前缀任意叠加）。 */
    static boolean isInterviewer(String agent) {
        return agent != null && agent.endsWith("interviewer");
    }
}
