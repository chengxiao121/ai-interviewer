package com.xiao.aiagent.controller;

import com.xiao.aiagent.dto.CreateKnowledgeBaseResult;
import com.xiao.aiagent.dto.KnowledgeBaseDto;
import com.xiao.aiagent.dto.QuestionPreviewDto;
import com.xiao.aiagent.service.QuestionBankService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库管理接口（阶段 12 新增）——「上传入库」路径的 HTTP 面。
 *
 * 为什么是「纯文本体」而不是 multipart（对计划文档"multipart 上传"的修正）：
 * 本项目是 WebFlux 栈（Reactor Netty），Spring MVC 的 MultipartFile 不生效
 * （MaterialController 阶段 7 实测踩坑 400）；且题库上传沿用 MaterialStoreService
 * 的文本约定——前端 file.text() 读出后 POST body 最简单可靠，元信息走 query 参数。
 * 体积限制由 spring.codec.max-in-memory-size 兜底。
 *
 * 与旧 /api/knowledge（向量同步）的关系：并存至阶段 7，届时随整条向量链路一起删除；
 * 新前端只用本接口，旧的"同步目录"按钮在阶段 6 前端改造时移除。
 *
 * 业务校验（重名 / 解析 0 题 / 格式）在 QuestionBankService 抛 IllegalArgumentException
 * → GlobalExceptionHandler 归口 400 中文提示，Controller 不再包一层。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseAdminController {

    private final QuestionBankService questionBankService;

    public KnowledgeBaseAdminController(QuestionBankService questionBankService) {
        this.questionBankService = questionBankService;
    }

    /**
     * 新建知识库（上传题库文档）：解析「【考点】+【参考答案】」切题落库，原文不保存。
     *
     * @param name        知识库名（必填，唯一）
     * @param description 描述（可选）
     * @param fileName    原始文件名（可选，扩展名校验 txt/md + 溯源展示）
     * @param content     文档全文（body，UTF-8）
     * @return 建库信息 + 解析统计（parsedCount/skippedCount 供前端提示"跳过了几道"）
     */
    @PostMapping(consumes = MediaType.TEXT_PLAIN_VALUE)
    public CreateKnowledgeBaseResult create(@RequestParam("name") String name,
                                            @RequestParam(value = "description", required = false) String description,
                                            @RequestParam(value = "fileName", required = false) String fileName,
                                            @RequestBody String content) {
        return questionBankService.create(name, description, fileName, content);
    }

    /** 知识库列表（含题目数与分类分布，按创建时间倒序）——入场选择器与管理页共用 */
    @GetMapping
    public List<KnowledgeBaseDto> list() {
        return questionBankService.list();
    }

    /** 题目预览（前 limit 道按原序，默认 20）——上传后自查解析质量 */
    @GetMapping("/{id}/questions")
    public List<QuestionPreviewDto> previewQuestions(@PathVariable Long id,
                                                     @RequestParam(value = "limit", required = false) Integer limit) {
        return questionBankService.previewQuestions(id, limit);
    }

    /** 删除知识库（连带删题；历史会话不受影响，卡片库名降级为空） */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        questionBankService.delete(id);
    }
}
