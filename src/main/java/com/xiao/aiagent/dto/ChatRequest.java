package com.xiao.aiagent.dto;

import java.util.List;

/**
 * chat 请求体（阶段 8）：
 *   - candidateName：候选人姓名（入场首轮必传；已有绑定的会话可省略）；
 *   - materialIds：上传资料 id（入场首轮必传且须含简历；已有绑定的会话由绑定决定）。
 * （原内联在 AssistantController 中，P3 重构抽出）
 */
public record ChatRequest(String userMessage, String sessionId, String candidateName,
                          List<String> materialIds) {
}
