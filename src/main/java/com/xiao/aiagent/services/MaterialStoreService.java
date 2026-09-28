package com.xiao.aiagent.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上传资料存储服务（阶段 7 新增）——JD/简历上传直通路径的"文件侧"。
 *
 * 定位：把"用户点按钮选文件"这个确定性动作落成稳定的 {materialId → 文本} 存储，
 * 供聊天入口绕过消息正则识别直接进资料流水线（见 MaterialInterviewPipeline.chatWithMaterials）。
 *
 * 设计要点：
 *   1. materialId = 类型 + 内容 MD5 —— 同类型同内容天然同 id，重复上传幂等去重
 *      （与知识库"MD5 内容哈希幂等同步"同一判据，省一次分析官 LLM 调用）；
 *   2. 只收 txt/md 纯文本——JD/简历分析官消费的就是文本；PDF/DOCX 解析留给完整版（Tika）；
 *      也因此上传走「纯文本体」而非 multipart：本项目是 WebFlux 栈（Reactor Netty），
 *      文本资料用 body 直传最简单，前端 file.text() 读出后 POST 即可；
 *   3. 存储放 ./data/uploads/（已在 .gitignore 的 data/ 内，本地资料不进 git）；
 *   4. materialId 同时是路径的唯一来源，格式强校验（类型前缀 + 32 位十六进制），
 *      杜绝路径穿越——上传接口的第一个安全底线。
 */
@Service
public class MaterialStoreService {

    private static final Logger log = LoggerFactory.getLogger(MaterialStoreService.class);

    /** 允许的文本扩展名（按文件名校验；分析官消费纯文本，pdf/docx 留给完整版） */
    private static final Set<String> ALLOWED_EXT = Set.of("txt", "md", "markdown");
    /** 抽取文本上限（超出截断）：JD/简历文本 6 万字符已远超需要，防超大文件撑爆上下文 */
    private static final int MAX_CHARS = 60_000;
    /** materialId 白名单格式：类型前缀 + 32 位十六进制 MD5（同时是防路径穿越的唯一闸门） */
    private static final Pattern ID_PATTERN = Pattern.compile("^(JD|RESUME)-[0-9a-f]{32}$");

    private final Path rootDir;
    private final ObjectMapper objectMapper;

    public MaterialStoreService(@Value("${app.materials.upload-dir:./data/uploads}") String uploadDir,
                                ObjectMapper objectMapper) throws IOException {
        this.rootDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        Files.createDirectories(rootDir);
        log.info("上传资料存储就绪：{}", rootDir);
    }

    /** 上传资料元数据（落盘 sidecar JSON，供追溯；id 本身已编码类型与内容哈希） */
    public record MaterialMeta(String materialId, String type, String fileName,
                               String md5, int charCount, String createdAt) {
    }

    /** 上传接口返回体 */
    public record UploadResult(String materialId, String type, String fileName,
                               int charCount, boolean duplicated) {
    }

    /**
     * 保存一份上传资料：校验 → MD5 幂等落盘。
     *
     * @param content  资料文本（前端从 txt/md 文件读出的纯文本）
     * @param type     资料类型（JD/RESUME，大小写不敏感）；空则从文件名推断，推断不出报 400
     * @param fileName 原始文件名（仅追溯用）
     */
    public UploadResult save(String content, String type, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            fileName = "material.txt";
        }

        String ext = extension(fileName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持 txt/md 文本文件，收到：" + fileName);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文件内容为空：" + fileName);
        }
        if (content.length() > MAX_CHARS) {
            log.warn("上传资料超过 {} 字符，已截断：{}", MAX_CHARS, fileName);
            content = content.substring(0, MAX_CHARS);
        }

        String normalizedType = normalizeType(type, fileName);
        String md5 = md5Hex(normalizedType + "\n" + content);
        String materialId = normalizedType + "-" + md5;

        Path textPath = rootDir.resolve(materialId + ".txt");
        boolean duplicated = Files.exists(textPath);
        if (duplicated) {
            log.info("上传资料命中去重（同类型同内容），复用现有 id：{}", materialId);
        } else {
            try {
                Files.writeString(textPath, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("资料写盘失败：" + e.getMessage(), e);
            }
            writeMeta(new MaterialMeta(materialId, normalizedType, fileName, md5, content.length(),
                    LocalDateTime.now().toString()));
            log.info("上传资料入库：id={}, type={}, file={}, chars={}",
                    materialId, normalizedType, fileName, content.length());
        }
        return new UploadResult(materialId, normalizedType, fileName, content.length(), duplicated);
    }

    /**
     * 按 materialId 列表读取资料文本，按 Material 类型归组（同类型多份拼接）。
     * 供聊天入口直通资料流水线用——类型在这里是确定值，不再做消息正则识别。
     */
    public Map<MaterialInterviewPipeline.Material, String> loadAsTexts(List<String> materialIds) throws IOException {
        if (materialIds == null || materialIds.isEmpty()) {
            throw new IllegalArgumentException("materialIds 为空");
        }
        Map<MaterialInterviewPipeline.Material, String> texts = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(materialIds)) {   // 去重保序
            if (id == null || !ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("非法的资料 id：" + id);
            }
            Path textPath = rootDir.resolve(id + ".txt");
            if (!Files.exists(textPath)) {
                throw new IllegalArgumentException("资料不存在或已被清理：" + id);
            }
            MaterialInterviewPipeline.Material material = id.startsWith("JD-")
                    ? MaterialInterviewPipeline.Material.JD
                    : MaterialInterviewPipeline.Material.RESUME;
            texts.merge(material, Files.readString(textPath, StandardCharsets.UTF_8), (a, b) -> a + "\n\n" + b);
        }
        return texts;
    }

    /** 类型归一：显式指定优先；否则按文件名推断（简历/resume/cv → RESUME，其余 JD 倾向） */
    private String normalizeType(String type, String fileName) {
        if (type != null && !type.isBlank()) {
            String t = type.trim().toUpperCase(Locale.ROOT);
            if (t.equals("JD") || t.equals("RESUME")) {
                return t;
            }
            throw new IllegalArgumentException("资料类型仅支持 JD / RESUME，收到：" + type);
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("简历") || lower.contains("resume") || lower.matches(".*(^|[^a-z])cv([^a-z]|$).*")) {
            return "RESUME";
        }
        if (lower.contains("jd") || fileName.contains("岗位") || fileName.contains("职位")) {
            return "JD";
        }
        throw new IllegalArgumentException("无法从文件名识别资料类型，请指定 JD 或 RESUME：" + fileName);
    }

    private void writeMeta(MaterialMeta meta) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(rootDir.resolve(meta.materialId() + ".json").toFile(), meta);
        } catch (IOException e) {
            // 元数据只是追溯辅助，id 本身已编码类型+哈希，写失败不影响功能
            log.warn("写入资料元数据失败（不影响功能）：{}", e.getMessage());
        }
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

}
