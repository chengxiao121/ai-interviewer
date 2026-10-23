package com.xiao.aiagent.dto;

/**
 * 上传资料元数据（落盘 sidecar JSON，供追溯；id 本身已编码类型与内容哈希）。
 * （原内联在 MaterialStoreService 中，P3 重构抽出）
 */
public record MaterialMeta(String materialId, String type, String fileName,
                           String md5, int charCount, String createdAt) {
}
