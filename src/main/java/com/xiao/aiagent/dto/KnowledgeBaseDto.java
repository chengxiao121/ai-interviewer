package com.xiao.aiagent.dto;

import java.util.Map;

/**
 * 知识库 DTO（阶段 12 新增）——列表页 / 入场选择器 / 新建结果共用。
 *
 * questionCount：该库 ACTIVE 题目数（组卷容量上限的直接依据，前端选择器展示"库名 · N 题"）；
 * categoryDistribution：分类 → 题目数（如 {集合:3, 并发:3}），让用户上传后一眼看出
 * 题库的考点构成、判断是否要重传；无分类题归入 null 键（前端按"未分类"展示或省略）。
 */
public record KnowledgeBaseDto(Long id, String name, String description, int questionCount,
                               Map<String, Integer> categoryDistribution,
                               String sourceFileName, String createdAt) {
}
