package com.xiao.aiagent.services;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent.ConcatenationMergeStrategy;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流层过滤（方案 b）单测：假 ChatModel + 真框架图，还原冒烟实录的"混流"场景。
 *
 * 假模型不花 DashScope 调用；模型只影响分块内容，事件的 agent()/node()/outputType
 * 身份元数据由图结构决定——测试结论对线上等价（探针实测依据见
 * InterviewerStreamFilter 类注释）。
 */
class InterviewerStreamFilterTest {

    /** 假模型：固定回复文本，stream 拆块模拟 token 流 */
    static class StubChatModel implements ChatModel {
        private final String reply;

        StubChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int len = Math.max(1, reply.length() / 3);
            List<ChatResponse> chunks = new ArrayList<>();
            for (int i = 0; i < reply.length(); i += len) {
                chunks.add(new ChatResponse(List.of(
                        new Generation(new AssistantMessage(reply.substring(i, Math.min(reply.length(), i + len)))))));
            }
            return Flux.fromIterable(chunks);
        }
    }

    private static final String ANALYZER_JSON =
            "{\"candidateOverview\":\"3年Java后端\",\"weaknessCandidates\":"
                    + "[{\"topic\":\"Redis\",\"verifyQuestion\":\"你说说缓存穿透？\"}]}";
    private static final String INTERVIEWER_REPLY =
            "你好，我们先从容器切入。\n### 第 1 题：Spring Boot 自动装配（Spring）\n说说自动装配的原理？";

    private static ReactAgent agent(String name, String reply) {
        return ReactAgent.builder()
                .name(name)
                .description(name + " 测试替身")
                .systemPrompt("你是 " + name)
                .model(new StubChatModel(reply))
                .outputKey(name)
                .build();
    }

    private static List<Message> collect(SequentialAgent pipeline) throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId("filter-test-" + System.nanoTime()).build();
        return InterviewerStreamFilter.interviewerOnly(pipeline.stream("你好，我带了简历", config))
                .collectList()
                .block();
    }

    @Nested
    @DisplayName("身份白名单谓词")
    class 谓词判定 {
        @Test
        void 子图前缀任意叠加都命中() {
            assertTrue(InterviewerStreamFilter.isInterviewer("interviewer"), "顶层直连");
            assertTrue(InterviewerStreamFilter.isInterviewer("subgraph_interviewer"), "一层嵌套（实测形态）");
            assertTrue(InterviewerStreamFilter.isInterviewer("subgraph_subgraph_interviewer"), "路由图→流水线→面试官");
        }

        @Test
        void 分析官与路由器被拦() {
            assertFalse(InterviewerStreamFilter.isInterviewer("subgraph_resume-analyzer"));
            assertFalse(InterviewerStreamFilter.isInterviewer("subgraph_jd-analyzer"));
            assertFalse(InterviewerStreamFilter.isInterviewer("subgraph_code-analyzer"));
            assertFalse(InterviewerStreamFilter.isInterviewer("intent-router"), "路由 LLM 的决策文本不放行");
            assertFalse(InterviewerStreamFilter.isInterviewer(null), "无身份事件不放行");
        }
    }

    @Nested
    @DisplayName("双 Agent 顺序图（4.1 代码评审同构）")
    class 顺序图 {
        @Test
        @DisplayName("过滤后只剩面试官回复，拼接完整、不含分析官 JSON")
        void 只放行面试官() throws Exception {
            SequentialAgent pipeline = SequentialAgent.builder()
                    .name("material-interview")
                    .subAgents(List.of(agent("resume-analyzer", ANALYZER_JSON),
                            agent("interviewer", INTERVIEWER_REPLY)))
                    .build();

            List<Message> messages = collect(pipeline);

            String joined = messages.stream().map(Message::getText).collect(Collectors.joining());
            assertEquals(INTERVIEWER_REPLY, joined, "过滤后应恰为面试官完整回复");
            assertFalse(joined.contains("verifyQuestion"), "分析官 JSON（含 verifyQuestion）不得外泄");
            assertFalse(joined.contains("candidateOverview"), "分析官字段不得外泄");
        }
    }

    @Nested
    @DisplayName("并行分析官 + 面试官（4.2 综合流水线同构）")
    class 并行图 {
        @Test
        @DisplayName("多分析官并行混流，过滤后仍只剩面试官回复")
        void 并行分析官被拦() throws Exception {
            ParallelAgent analyzers = ParallelAgent.builder()
                    .name("parallel-analyzers")
                    .subAgents(List.of(agent("jd-analyzer", "{\"position\":\"Java高开\"}"),
                            agent("resume-analyzer", ANALYZER_JSON)))
                    .mergeStrategy(new ConcatenationMergeStrategy())
                    .build();
            SequentialAgent pipeline = SequentialAgent.builder()
                    .name("material-interview")
                    .subAgents(List.of(analyzers, agent("interviewer", INTERVIEWER_REPLY)))
                    .build();

            List<Message> messages = collect(pipeline);

            String joined = messages.stream().map(Message::getText).collect(Collectors.joining());
            assertEquals(INTERVIEWER_REPLY, joined, "并行场景过滤后应恰为面试官完整回复");
            assertFalse(joined.contains("verifyQuestion"));
            assertFalse(joined.contains("position"), "另一个分析官的产物同样被拦");
        }
    }
}
