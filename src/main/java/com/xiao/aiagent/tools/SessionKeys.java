package com.xiao.aiagent.tools;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 身份 key 工具（阶段 4.2 新增 + 4.3 扩展）——从工具上下文里提取本次运行的业务身份键。
 *
 * 为什么需要它（4.2 验收实测发现的坑）：
 *   LlmRoutingAgent / SequentialAgent 这类编排 Agent 派生子 Agent 时，
 *   框架会给子 Agent 的 threadId 自动追加 `_subgraph_<agent名>` 后缀，
 *   于是子图里运行的工具（saveCodeProfile/getCodeFacts/scoreRecord/questionStats）
 *   看到的 threadId 形如 `route-test-3_subgraph_code-review-pipeline`。
 *   若直接把带后缀的 threadId 当 sessionId 落库/查询，会出现三种故障：
 *     1. 代码评审复用快路径失效：外层查 existsBySessionId(干净 id) 永远查不到带后缀的记录；
 *     2. 面试官（更内层子图）的 getCodeFacts 与落库 key 对不上，取不到清单；
 *     3. scoreRecord 落在带后缀 key 下，按 candidateId 跨会话聚合会乱。
 *
 * 修复：所有 @Tool 工具统一走本类提取身份键：
 *   - rootSessionId()：把 threadId 在第一个 `_subgraph_` 处截断，得到进入编排时的原始会话 id；
 *   - candidateId()：从 RunnableConfig 的 metadata 里取候选人 id（4.3 新增，入口处写入）。
 *
 * 候选人 id 为什么放 metadata 而不是拼进 threadId？
 *   threadId 是框架语义固定的"会话标识"（还会被子图自动加后缀）；
 *   candidateId 是业务自定义身份，走 RunnableConfig.metadata 键值对，
 *   与 threadId 同对象透传、互不干扰，将来要扩展更多身份维度（如岗位 id）也按同样方式加。
 */
public final class SessionKeys {

    private SessionKeys() {
    }

    /** 候选人 id 在 RunnableConfig metadata 中的键（4.3 新增） */
    public static final String CANDIDATE_ID_KEY = "candidateId";

    /** 默认候选人 id：请求未显式传 candidateId 时的占位值，所有会话归并到同一候选人（跨会话聚合立即可用） */
    public static final String DEFAULT_CANDIDATE_ID = "default";

    /**
     * 从工具上下文取【根会话 id】。
     * 子图 threadId 形如 `root_subgraph_agentA_subgraph_agentB`，取最前面的 root 段。
     *
     * @param toolContext 框架注入的工具上下文（含当前子图的 RunnableConfig）
     * @return 根会话 id（无后缀），取不到时兜底 "unknown"
     */
    public static String rootSessionId(ToolContext toolContext) {
        String threadId = ToolContextHelper.getConfig(toolContext)
                .flatMap(RunnableConfig::threadId)
                .orElse("unknown");
        int idx = threadId.indexOf("_subgraph_");
        return idx >= 0 ? threadId.substring(0, idx) : threadId;
    }

    /**
     * 从工具上下文取【候选人 id】（4.3 新增）。
     * 入口（Controller/各编排器）建 RunnableConfig 时用 addMetadata(CANDIDATE_ID_KEY, candidateId) 写入，
     * 工具侧从同一 config 对象读回（子图透传与 threadId 同理）。
     * 未写入或取不到时兜底 DEFAULT_CANDIDATE_ID，跨会话聚合仍可用。
     *
     * @param toolContext 框架注入的工具上下文（含当前子图的 RunnableConfig）
     * @return 候选人 id
     */
    public static String candidateId(ToolContext toolContext) {
        return ToolContextHelper.getConfig(toolContext)
                .flatMap(config -> config.metadata(CANDIDATE_ID_KEY))
                .map(Object::toString)
                .orElse(DEFAULT_CANDIDATE_ID);
    }

}