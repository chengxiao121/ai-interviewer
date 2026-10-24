package com.xiao.aiagent.dto;

import java.util.List;

/**
 * 会话绑定 DTO（resumeFileName/jdFileName 供会话卡片展示，可为 null）。
 * （原内联在 InterviewSessionController 中，P3 重构抽出）
 * status（阶段 11 新增）：IN_PROGRESS/FINISHED——报告入口与"已结束"标签的状态依据。
 *
 * mode / kbId / kbName（阶段 12 新增）：前端据此区分两种面试的展示——
 * 会话卡片按模式显示不同信息（资料模式显示简历/JD 名，知识库模式显示题库名），
 * 列表页按模式筛选。kbName 读不到（知识库已被删除）时为 null，前端降级不显示，
 * 不炸接口（与 resumeFileName 的"读不到元数据静默跳过"同一处理哲学）。
 */
public record InterviewSessionDto(String sessionId, String candidateId,
                                  List<String> materialIds, String createdAt,
                                  String resumeFileName, String jdFileName,
                                  String status, String mode, Long kbId, String kbName) {
}
