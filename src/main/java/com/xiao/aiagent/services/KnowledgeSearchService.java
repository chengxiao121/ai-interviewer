package com.xiao.aiagent.services;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 题库知识检索组件（阶段 4.2 重构，Rule of Three 触发点）。
 *
 * 为什么抽它：题库 RAG 前置检索的同一段代码（similaritySearch → topK=3 → 门槛 0.7 → 拼 system 文本）
 * 在四个类里各复制了一遍（InterviewAssistant / CodeReviewPipeline / AgentRouter / CombinedInterviewPipeline）。
 * 出现第四次复制时就是抽公共组件的信号——"三次重复忍、四次不重复忍"，
 * 把变化点（同一个题库向量库 + 同一套检索参数 + 同一段拼装文案）收拢成唯一的真源。
 *
 * 设计要点：
 *   - 本项目唯一的知识检索来源就是题库向量库（ai-agent-index），参数统一，无需可配；
 *   - 返回【整段 system 消息文本】（含"请优先参考"引导语），调用方直接 new SystemMessage(...) 即可，
 *     空结果返回空串，调用方据此跳过注入。
 */
@Component
public class KnowledgeSearchService {

    private final VectorStore vectorStore;

    public KnowledgeSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 按查询词检索题库，拼装成一段可注入 system 消息的知识文本。
     *
     * @param query 用户本轮消息（检索语义上最贴近"和题目相关"的那段）
     * @return 【题库知识】system 文本；无命中（低于 0.7 相似度或空库）返回空串
     */
    public String search(String query) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .similarityThreshold(0.7)
                        .build());
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【题库知识】以下内容来自面试题库，出题时请优先参考：\n");
        for (Document d : hits) {
            sb.append("- ").append(d.getText()).append("\n");
        }
        return sb.toString();
    }

}