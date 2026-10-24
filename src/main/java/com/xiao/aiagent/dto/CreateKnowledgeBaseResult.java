package com.xiao.aiagent.dto;

/**
 * 新建知识库的结果（阶段 12 新增）——建库信息 + 解析统计。
 *
 * parsedCount/skippedCount 让用户上传后自查解析质量："16 道解析成功、0 道跳过"
 * 说明文档格式没问题；skipped > 0 时前端应提示"有 N 道缺【参考答案】被跳过"，
 * 这是计划文档风险 7 的显式呈现要求（不静默吞掉）。
 */
public record CreateKnowledgeBaseResult(KnowledgeBaseDto knowledgeBase,
                                        int parsedCount, int skippedCount) {
}
