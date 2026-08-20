package com.xiao.aiagent.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 工具安全过滤器：只放行只读工具，拦截写/删除等破坏性操作。
 * <p>
 * 背景：Filesystem MCP Server 默认暴露 read_file / write_file / edit_file /
 * move_file / search_files / list_directory / get_file_info / delete_file 等全部工具。
 * 面试 agent 只该读取候选人代码做评审提问，不该修改或删除任何文件，因此需要把写操作挡掉。
 * <p>
 * 判断依据：MCP 规范为每个工具声明了 {@code annotations.readOnlyHint}，
 * true 表示该工具不会产生副作用（只读）。优先用这个标准字段判断；
 * 兜底用工具名白名单（部分老版本 server 可能不声明 annotations）。
 * <p>
 * 注册方式：实现 {@link McpToolFilter}（继承 BiPredicate）并标注 @Component，
 * 会被 MCP 自动装配拾取，应用到所有 MCP 客户端发现的工具上。
 */
@Component
public class ReadOnlyMcpToolFilter implements McpToolFilter {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyMcpToolFilter.class);

    /**
     * 工具名白名单兜底：annotations 缺失或 readOnlyHint 为 null 时，
     * 只要工具名命中这份列表也放行。覆盖 Filesystem / Fetch 两个 server 的只读工具。
     */
    private static final List<String> READONLY_TOOL_NAMES = List.of(
            // Filesystem MCP 只读工具
            "read_file", "read_text_file", "read_multiple_text_files",
            "list_directory", "directory_tree", "search_files", "get_file_info",
            // Fetch MCP 只读工具
            "fetch"
    );

    @Override
    public boolean test(McpConnectionInfo connectionInfo, McpSchema.Tool tool) {
        boolean allowed = isReadOnly(tool);
        if (allowed) {
            log.debug("MCP 工具放行：{}", tool.name());
        } else {
            log.warn("MCP 工具拦截（非只读）：{}", tool.name());
        }
        return allowed;
    }

    /**
     * 判断一个工具是否只读：
     * 1. 优先看 MCP 规范的 annotations.readOnlyHint（true 才放行）
     * 2. annotations 缺失时退回工具名白名单兜底
     */
    private boolean isReadOnly(McpSchema.Tool tool) {
        McpSchema.ToolAnnotations annotations = tool.annotations();
        if (annotations != null && annotations.readOnlyHint() != null) {
            return Boolean.TRUE.equals(annotations.readOnlyHint());
        }
        return READONLY_TOOL_NAMES.contains(tool.name());
    }

}
