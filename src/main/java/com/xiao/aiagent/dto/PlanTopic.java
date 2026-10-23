package com.xiao.aiagent.dto;

/**
 * 考点计划条目（词表的最小单元）。priority/difficulty 取值见 InterviewPlanService.normalize* 的合法值域。
 * （原内联在 InterviewPlanService 中，P3 重构抽出——评估、报告、计划三层共用）
 */
public record PlanTopic(String topic, String priority, String difficulty) {
}
