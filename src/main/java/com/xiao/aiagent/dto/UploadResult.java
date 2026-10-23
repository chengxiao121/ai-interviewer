package com.xiao.aiagent.dto;

/**
 * 上传接口返回体。
 * （原内联在 MaterialStoreService 中，P3 重构抽出）
 */
public record UploadResult(String materialId, String type, String fileName,
                           int charCount, boolean duplicated) {
}
