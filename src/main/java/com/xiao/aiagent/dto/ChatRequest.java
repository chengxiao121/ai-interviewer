package com.xiao.aiagent.dto;

import java.util.List;

/**
 * chat 请求体（阶段 8）：
 *   - candidateName：候选人姓名（入场首轮必传；已有绑定的会话可省略）；
 *   - materialIds：上传资料 id（资料模式入场首轮必传且须含简历；已有绑定的会话由绑定决定）。
 * （原内联在 AssistantController 中，P3 重构抽出）
 *
 * 阶段 12 新增（双模式入场）：
 *   - mode：面试模式 MATERIAL（资料面试，缺省值，兼容旧前端）/ KNOWLEDGE（知识库面试），
 *     仅新会话首轮有意义；已绑定的会话再带 mode 且与绑定不符 → 400 显式拒绝（不静默忽略）；
 *   - kbId：知识库 id（KNOWLEDGE 模式必传）；
 *   - questionCount：组卷题数（KNOWLEDGE 模式可选，缺省 20，允许 5~50）。
 */
public record ChatRequest(String userMessage, String sessionId, String candidateName,
                          List<String> materialIds,
                          String mode, Long kbId, Integer questionCount) {
}
