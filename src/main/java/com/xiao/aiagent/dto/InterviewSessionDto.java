package com.xiao.aiagent.dto;

import java.util.List;

/**
 * 会话绑定 DTO（resumeFileName/jdFileName 供会话卡片展示，可为 null）。
 * （原内联在 InterviewSessionController 中，P3 重构抽出）
 */
public record InterviewSessionDto(String sessionId, String candidateId,
                                  List<String> materialIds, String createdAt,
                                  String resumeFileName, String jdFileName) {
}
