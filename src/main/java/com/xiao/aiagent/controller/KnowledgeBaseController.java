package com.xiao.aiagent.controller;

import com.xiao.aiagent.repository.KnowledgeDocumentRepository;
import com.xiao.aiagent.services.KnowledgeBaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理：手动触发同步 + 查看入库状态
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentRepository repository;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService,
                                   KnowledgeDocumentRepository repository) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.repository = repository;
    }

    /** 手动触发同步：外部目录有文件就同步外部，否则同步 classpath 兜底 */
    @PostMapping("/sync")
    public Map<String, Object> sync() {
        knowledgeBaseService.syncKnowledgeBase("file:./data/knowledges/", "EXTERNAL");
        knowledgeBaseService.syncKnowledgeBase("classpath:knowledges/", "CLASSPATH");
        long count = repository.count();
        return Map.of(
                "message", "同步完成",
                "totalDocuments", count
        );
    }

    /** 查看当前入库的所有文档元数据 */
    @GetMapping("/documents")
    public List<Map<String, Object>> listDocuments() {
        return repository.findAll().stream()
                .map(doc -> Map.<String, Object>of(
                        "fileName", doc.getFileName(),
                        "chunkCount", doc.getChunkCount(),
                        "status", doc.getStatus().name(),
                        "source", doc.getSource(),
                        "ingestedAt", doc.getIngestedAt().toString()
                ))
                .toList();
    }
}
