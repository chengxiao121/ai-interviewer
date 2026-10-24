package com.xiao.aiagent.dto;

/**
 * 知识库题目预览条目（阶段 12 新增）——管理页"点开看题目"用，按库内原序（seq）展示。
 * referenceAnswer 完整返回：用户上传后自查"解析出的题和答案是不是完整的"就靠它。
 */
public record QuestionPreviewDto(Integer seq, String category, String stem, String referenceAnswer) {
}
