package com.xiao.aiagent.services;

import com.xiao.aiagent.entity.KnowledgeDocStatus;
import com.xiao.aiagent.entity.KnowledgeDocument;
import com.xiao.aiagent.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeDocumentRepository repository;
    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourcePatternResolver;
    private final TokenTextSplitter splitter;

    public KnowledgeBaseService(KnowledgeDocumentRepository repository,
                                VectorStore vectorStore,
                                ResourcePatternResolver resourcePatternResolver) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.resourcePatternResolver = resourcePatternResolver;
        // 题库是「【考点】+【参考答案】」问答结构，默认 800 token/块会把多道题揉进一个向量块，
        // 检索粒度太粗；降到 ~300 token 让一块基本只含 1~2 道题。该分块器切分时会优先落在 \n 边界，
        // 与题库"空行分隔题目"的格式配合；minChunkSizeChars=100 保证块内边界太靠前时宁可取满整块。
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(300)
                .withMinChunkSizeChars(100)
                .build();
    }

    /**
     * 同步知识库：扫描目录 → 分块 → 入库（幂等增量同步）
     *
     * @param resourceLocation 资源路径，如 "file:./data/knowledges/" 或 "classpath:knowledges/"
     * @param source           数据来源标识：EXTERNAL / CLASSPATH
     */
    public void syncKnowledgeBase(String resourceLocation, String source) {
        log.info("===== 开始同步知识库 [{}] 路径：{} =====", source, resourceLocation);
        try {
            // 1. 列出所有 .txt 文件
            Resource[] resources = resourcePatternResolver.getResources(resourceLocation + "*.txt");
            Set<String> currentFileNames = new HashSet<>();

            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName == null || !fileName.endsWith(".txt")) continue;
                currentFileNames.add(fileName);

                try {
                    ingestFile(resource, source);
                } catch (Exception e) {
                    log.error("文档处理失败：{}，原因：{}", fileName, e.getMessage());
                }
            }

            // 2. 清理已删除的文档（增量删除，仅限当前数据源）
            removeDeletedFiles(currentFileNames, source);

            log.info("===== 知识库同步完成 [{}]，共 {} 个文档 =====", source, currentFileNames.size());
        } catch (IOException e) {
            log.error("扫描知识库目录失败：{}", resourceLocation, e);
        }
    }

    /** 入库单个文件（幂等：哈希未变则跳过） */
    private void ingestFile(Resource resource, String source) throws IOException {
        String fileName = resource.getFilename();

        // 读内容 + 计算 MD5 哈希
        String content = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        String contentHash = DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8));

        // 查已入库的元数据
        Optional<KnowledgeDocument> existing = repository.findByFileName(fileName);

        // 哈希相同 → 跳过（幂等）
        if (existing.isPresent() && existing.get().getContentHash().equals(contentHash)) {
            log.debug("文档未变更，跳过：{}", fileName);
            return;
        }

        // ===== 文档有变化或新文档 → 重新入库 =====
        if (existing.isPresent()) {
            log.info("文档已变更，重新入库：{}", fileName);
            // 删除旧向量块（按 docId 拼 chunk ID）
            deleteVectorChunks(fileName, existing.get().getChunkCount());
        } else {
            log.info("发现新文档，开始入库：{}", fileName);
        }

        // 分块：TokenTextSplitter 按 token 数切分
        List<Document> rawChunks = splitter.split(new Document(content));
        // 构造带自定义 ID 和元数据的 Document
        List<Document> chunks = new ArrayList<>();
        for (int i = 0; i < rawChunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>(rawChunks.get(i).getMetadata());
            metadata.put("docId", fileName);
            metadata.put("chunkIndex", i);
            // ID = 文件名_序号（如 "java-interview-questions.txt_0"），方便后续按前缀删除
            chunks.add(new Document(fileName + "_" + i, rawChunks.get(i).getText(), metadata));
        }

        log.info("文档 {} 切分为 {} 块", fileName, chunks.size());

        // 写入 Redis 向量库
        vectorStore.add(chunks);

        // 写入/更新 PG 元数据表
        KnowledgeDocument doc = existing.orElse(null);
        if (doc == null) {
            doc = new KnowledgeDocument(fileName, chunks.size(), contentHash,
                    KnowledgeDocStatus.INGESTED, source, LocalDateTime.now());
        } else {
            doc.setChunkCount(chunks.size());
            doc.setContentHash(contentHash);
            doc.setStatus(KnowledgeDocStatus.INGESTED);
            doc.setIngestedAt(LocalDateTime.now());
        }
        repository.save(doc);

        log.info("文档 {} 入库完成（{} 块）", fileName, chunks.size());
    }

    /** 从 PG 元数据中删除那些文件已不存在的记录（只清理当前数据源，避免误删其他来源的文档） */
    private void removeDeletedFiles(Set<String> currentFileNames, String source) {
        List<KnowledgeDocument> all = repository.findAll();
        for (KnowledgeDocument doc : all) {
            if (doc.getSource().equals(source) && !currentFileNames.contains(doc.getFileName())) {
                log.info("文档已从目录中删除，清理入库记录：{}", doc.getFileName());
                deleteVectorChunks(doc.getFileName(), doc.getChunkCount());
                repository.delete(doc);
            }
        }
    }

    /** 按 docId 和 chunkCount 构造所有 chunk ID → 从向量库删除 */
    private void deleteVectorChunks(String docId, int chunkCount) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            ids.add(docId + "_" + i);
        }
        vectorStore.delete(ids);
        log.debug("删除向量块：docId={}，共 {} 块", docId, chunkCount);
    }
}
