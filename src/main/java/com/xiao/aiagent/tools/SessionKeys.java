package com.xiao.aiagent.tools;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 会话 key 工具（阶段 4.2 修复）——从工具上下文里提取【根会话 id】。
 *
 * 为什么需要它（验收实测发现的坑）：
 *   LlmRoutingAgent / SequentialAgent 这类编排 Agent 派生子 Agent 时，
 *   框架会给子 Agent 的 threadId 自动追加 `_subgraph_<agent名>` 后缀，
 *   于是子图里运行的工具（saveCodeProfile/getCodeFacts/scoreRecord/questionStats）
 *   看到的 threadId 形如 `route-test-3_subgraph_code-review-pipeline`。
 *   若直接把带后缀的 threadId 当 sessionId 落库/查询，会出现三种故障：
 *     1. 代码评审复用快路径失效：外层查 existsBySessionId(干净 id) 永远查不到带后缀的记录；
 *     2. 面试官（更内层子图）的 getCodeFacts 与落库 key 对不上，取不到清单；
 *     3. scoreRecord 落在带后缀 key 下，将来 4.3 按 candidateId 跨会话聚合会乱。
 *
 * 修复：所有 @Tool 工具统一走本方法取 key，把 threadId 在第一个 `_subgraph_` 处截断，
 *   得到进入编排时的原始会话 id。根会话 id 才是业务上要隔离/聚合的维度。
 */
public final class SessionKeys {

    private SessionKeys() {
    }

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

}