package com.xiao.aiagent.service;

import com.xiao.aiagent.dto.CreateKnowledgeBaseResult;
import com.xiao.aiagent.dto.KnowledgeBaseDto;
import com.xiao.aiagent.dto.QuestionPreviewDto;
import com.xiao.aiagent.entity.KnowledgeBase;
import com.xiao.aiagent.entity.QuestionBankItem;
import com.xiao.aiagent.repository.KnowledgeBaseRepository;
import com.xiao.aiagent.repository.QuestionBankRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 题库管理服务（阶段 12 新增）——知识库"上传入库"路径的唯一生产方。
 *
 * 职责：新建（解析 + 落题，单事务）、列表、删除（连带删题）、题目预览。
 * 与同名前缀的 KnowledgeBaseService（向量分块入库）职责不同：
 * 那个消费原文产向量块，这个消费原文产【题目实体】；本服务不碰向量库。
 *
 * 与 MaterialStoreService 沿用的约定（计划文档阶段 3 任务 2）：
 *   - 只收 txt/md 纯文本（扩展名白名单一致）；
 *   - 超长截断（题库给得更宽：200k 字符 ≈ 数百道题，仍防超大文件）；
 *   - 原文不落盘：解析完即弃，题目进 question_bank 表（决策 8）——
 *     面试时只读题目表，原文没有第二次被读取的场景，省掉一整套文件存储。
 *
 * 显式拒绝（不静默降级，与组卷"容量不足直接 400"同一哲学）：
 *   - 名称重复 → 400（名称是知识库的自然键，重名让选择器分不清）；
 *   - 解析出 0 道题 → 400（不建空库——空库既不能组卷，还会污染列表）。
 */
@Service
public class QuestionBankService {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankService.class);

    /** 允许的文本扩展名（与 MaterialStoreService 同白名单） */
    private static final Set<String> ALLOWED_EXT = Set.of("txt", "md", "markdown");
    /** 上限（超出截断）：题库比简历/JD 大得多，200k 字符 ≈ 数百道题 */
    private static final int MAX_CHARS = 200_000;
    private static final int MAX_NAME_LEN = 64;
    /** 预览默认条数（管理页一屏能看到题目全貌，又不至于把 500 题全吐出来） */
    private static final int PREVIEW_LIMIT = 20;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final QuestionBankRepository questionBankRepository;

    public QuestionBankService(KnowledgeBaseRepository knowledgeBaseRepository,
                               QuestionBankRepository questionBankRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.questionBankRepository = questionBankRepository;
    }

    /**
     * 新建知识库：校验 → 解析 → 落库（单事务：库和题同生共死，半途失败不留半套数据）。
     *
     * @param name        知识库名（唯一，用户手填）
     * @param description 描述（可选）
     * @param fileName    上传的原始文件名（仅扩展名校验与溯源；原文不保存）
     * @param content     文档全文
     */
    @Transactional
    public CreateKnowledgeBaseResult create(String name, String description, String fileName, String content) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("请填写知识库名称");
        }
        String normalizedName = name.strip();
        if (normalizedName.length() > MAX_NAME_LEN) {
            throw new IllegalArgumentException("知识库名称过长（最多 " + MAX_NAME_LEN + " 字符）");
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = "questions.txt";
        }
        if (!ALLOWED_EXT.contains(extension(fileName))) {
            throw new IllegalArgumentException("仅支持 txt/md 文本文件，收到：" + fileName);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文件内容为空：" + fileName);
        }
        if (content.length() > MAX_CHARS) {
            log.warn("题库文档超过 {} 字符，已截断：{}", MAX_CHARS, fileName);
            content = content.substring(0, MAX_CHARS);
        }
        if (knowledgeBaseRepository.findByName(normalizedName).isPresent()) {
            throw new IllegalArgumentException("已存在同名知识库：" + normalizedName + "，请换一个名称");
        }

        QuestionParser.ParseResult parsed = QuestionParser.parse(content);
        if (parsed.questions().isEmpty()) {
            throw new IllegalArgumentException(
                    "未从文档中解析出题目，请检查是否为「【考点】+【参考答案】」格式");
        }

        KnowledgeBase kb = new KnowledgeBase(normalizedName, description == null ? null : description.strip(),
                parsed.parsedCount(), fileName, md5Hex(content), LocalDateTime.now());
        final KnowledgeBase savedKb = knowledgeBaseRepository.save(kb);

        // 落题：seq 按文档原序 1..n（UNIQUE(kb_id, seq) 兜底，重复解析不会写两遍）
        List<QuestionBankItem> items = parsed.questions().stream()
                .map(q -> new QuestionBankItem(savedKb.getId(), 0, q.stem(), q.referenceAnswer(),
                        q.category(), QuestionBankItem.STATUS_ACTIVE, LocalDateTime.now()))
                .toList();
        List<QuestionBankItem> saved = questionBankRepository.saveAll(renumber(items));

        log.info("知识库入库：id={}, name={}, 解析 {} 题（跳过 {}）, source={}",
                savedKb.getId(), normalizedName, parsed.parsedCount(), parsed.skippedCount(), fileName);
        return new CreateKnowledgeBaseResult(toDto(savedKb, saved), parsed.parsedCount(), parsed.skippedCount());
    }

    /** 知识库列表（含题目数与分类分布），按创建时间倒序 */
    @Transactional(readOnly = true)
    public List<KnowledgeBaseDto> list() {
        return knowledgeBaseRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(kb -> toDto(kb, questionBankRepository.findByKbIdOrderBySeq(kb.getId())))
                .toList();
    }

    /**
     * 删除知识库：连带删除该库全部题目（单事务）。
     * 历史会话不受影响（绑定只存 kbId，无外键约束，会话卡片库名降级为空）。
     */
    @Transactional
    public void delete(Long id) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在或已删除：id=" + id));
        int removed = questionBankRepository.deleteByKbId(id);
        knowledgeBaseRepository.delete(kb);
        log.info("知识库已删除：id={}, name={}, 连带删题 {} 道", id, kb.getName(), removed);
    }

    /** 题目预览（前 N 道按原序），供管理页上传后自查解析质量 */
    @Transactional(readOnly = true)
    public List<QuestionPreviewDto> previewQuestions(Long id, Integer limit) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在或已删除：id=" + id));
        int n = (limit == null || limit <= 0) ? PREVIEW_LIMIT : Math.min(limit, 200);
        return questionBankRepository.findByKbIdOrderBySeq(kb.getId()).stream()
                .limit(n)
                .map(q -> new QuestionPreviewDto(q.getSeq(), q.getCategory(), q.getStem(), q.getReferenceAnswer()))
                .toList();
    }

    /** seq 重编号（1..n，按解析原序）：新库没有历史序，直接顺序赋值 */
    private static List<QuestionBankItem> renumber(List<QuestionBankItem> items) {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setSeq(i + 1);
        }
        return items;
    }

    /** 实体 → DTO：题目数与分类分布从题目表实时算（单机自用，量级小，不做冗余同步） */
    private KnowledgeBaseDto toDto(KnowledgeBase kb, List<QuestionBankItem> items) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (QuestionBankItem q : items) {
            if (!QuestionBankItem.STATUS_ACTIVE.equals(q.getStatus())) {
                continue;
            }
            distribution.merge(q.getCategory(), 1, Integer::sum);
        }
        return new KnowledgeBaseDto(kb.getId(), kb.getName(), kb.getDescription(),
                distribution.values().stream().mapToInt(Integer::intValue).sum(),
                distribution, kb.getSourceFileName(),
                kb.getCreatedAt() == null ? null : kb.getCreatedAt().toString());
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
