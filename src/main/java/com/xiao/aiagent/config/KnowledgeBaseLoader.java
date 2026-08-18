package com.xiao.aiagent.config;
import com.xiao.aiagent.services.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时自动加载知识库。优先级：外部目录 > classpath 兜底
 */
@Component
public class KnowledgeBaseLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseLoader.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final ResourcePatternResolver resourcePatternResolver;

    @Value("${app.knowledge.path}")
    private String knowledgePath;   // 即 file:./data/knowledges/

    public KnowledgeBaseLoader(KnowledgeBaseService knowledgeBaseService,
                               ResourcePatternResolver resourcePatternResolver) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.resourcePatternResolver = resourcePatternResolver;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            doLoad();
        } catch (Exception e) {
            // 知识库加载失败不影响应用启动（只是没有 RAG 能力）
            log.error("知识库加载异常，应用继续启动", e);
        }
    }

    private void doLoad() throws IOException {
        // 1. 解析外部目录路径（去掉 file: 前缀）
        String fsPart = knowledgePath.substring("file:".length());
        Path dirPath = Paths.get(fsPart).toAbsolutePath().normalize();
        log.info("知识库目录：{}", dirPath);

        // 2. 目录不存在 → 创建，回退 classpath
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
            log.warn("外部知识库目录不存在，已自动创建：{}", dirPath);
            log.warn("请将题库文档放入该目录后重启，当前使用内置示例题库兜底");
            loadFromClasspath();
            return;
        }

        // 3. 目录存在但为空 → 回退 classpath
        Resource[] resources = resourcePatternResolver.getResources(knowledgePath + "*.txt");
        if (resources.length == 0) {
            log.warn("外部知识库目录为空，使用内置示例题库兜底");
            loadFromClasspath();
            return;
        }

        // 4. 目录存在且有 .txt 文件 → 使用外部题库
        log.info("使用外部题库文档：{}（{} 个文件）", dirPath, resources.length);
        knowledgeBaseService.syncKnowledgeBase(knowledgePath, "EXTERNAL");
    }

    private void loadFromClasspath() {
        log.info("使用内置示例题库：classpath:knowledges/");
        knowledgeBaseService.syncKnowledgeBase("classpath:knowledges/", "CLASSPATH");
    }
}
