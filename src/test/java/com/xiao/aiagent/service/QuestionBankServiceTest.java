package com.xiao.aiagent.service;

import com.xiao.aiagent.dto.CreateKnowledgeBaseResult;
import com.xiao.aiagent.dto.KnowledgeBaseDto;
import com.xiao.aiagent.dto.QuestionPreviewDto;
import com.xiao.aiagent.entity.KnowledgeBase;
import com.xiao.aiagent.entity.QuestionBankItem;
import com.xiao.aiagent.repository.KnowledgeBaseRepository;
import com.xiao.aiagent.repository.QuestionBankRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuestionBankService 单测（阶段 12）：新建/删除/预览的业务规则与显式拒绝分支。
 * 仓储全 mock（禁止起 Spring 上下文/连 PG）；事务与唯一约束兜底由数据库保证，不在此测。
 */
@DisplayName("QuestionBankService：知识库管理（解析入库/删除/预览）")
class QuestionBankServiceTest {

    private static final String VALID_DOC = """
            ## 分类A
            【考点】题一
            【参考答案】答案一

            ## 分类B
            【考点】题二
            【参考答案】答案二
            """;

    private KnowledgeBaseRepository kbRepository;
    private QuestionBankRepository questionRepository;
    private QuestionBankService service;

    @BeforeEach
    void setUp() {
        kbRepository = Mockito.mock(KnowledgeBaseRepository.class);
        questionRepository = Mockito.mock(QuestionBankRepository.class);
        service = new QuestionBankService(kbRepository, questionRepository);

        // 公共桩：保存时回填 id（模拟数据库 IDENTITY 生成），saveAll 原样返回
        when(kbRepository.save(any(KnowledgeBase.class))).thenAnswer(inv -> {
            KnowledgeBase kb = inv.getArgument(0);
            kb.setId(1L);
            return kb;
        });
        when(questionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kbRepository.findByName(any())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("新建知识库")
    class 新建 {

        @Test
        @DisplayName("正常文档：解析 2 题 → 落库 seq 连续 1..n，统计与分类分布正确")
        void 正常创建() {
            CreateKnowledgeBaseResult result =
                    service.create("Java 题库", "描述", "java.txt", VALID_DOC);

            assertEquals(2, result.parsedCount());
            assertEquals(0, result.skippedCount());

            ArgumentCaptor<List<QuestionBankItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(questionRepository).saveAll(captor.capture());
            List<QuestionBankItem> items = captor.getValue();
            assertEquals(2, items.size());
            assertEquals(1, items.get(0).getSeq());
            assertEquals(2, items.get(1).getSeq());
            assertEquals("题一", items.get(0).getStem());
            assertEquals("分类A", items.get(0).getCategory());
            assertEquals(1L, items.get(0).getKbId());
            assertEquals(QuestionBankItem.STATUS_ACTIVE, items.get(0).getStatus());
        }

        @Test
        @DisplayName("返回 DTO：题目数按 ACTIVE 实时算，分类分布正确")
        void dto字段() {
            CreateKnowledgeBaseResult result =
                    service.create("Java 题库", "描述", "java.md", VALID_DOC);

            KnowledgeBaseDto dto = result.knowledgeBase();
            assertEquals("Java 题库", dto.name());
            assertEquals(2, dto.questionCount());
            assertEquals(2, dto.categoryDistribution().get("分类A") + dto.categoryDistribution().get("分类B"));
            assertEquals("java.md", dto.sourceFileName());
            assertEquals("描述", dto.description());
        }

        @Test
        @DisplayName("重名知识库 → 400 明确拒绝，不覆盖已有库")
        void 重名拒绝() {
            when(kbRepository.findByName("Java 题库")).thenReturn(Optional.of(new KnowledgeBase()));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.create("Java 题库", null, "a.txt", VALID_DOC));

            assertTrue(e.getMessage().contains("同名"));
            verify(kbRepository, never()).save(any());
        }

        @Test
        @DisplayName("解析出 0 道题 → 拒绝建库（不静默建空库），提示含格式说明")
        void 零题拒绝() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.create("坏库", null, "a.txt", "# 普通文档\n\n没有题目标记。"));

            assertTrue(e.getMessage().contains("未从文档中解析出题目"));
            verify(kbRepository, never()).save(any());
        }

        @Test
        @DisplayName("非 txt/md 文件 → 拒绝；空内容 → 拒绝；空名称 → 拒绝")
        void 前置校验() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.create("库", null, "questions.pdf", VALID_DOC));
            assertThrows(IllegalArgumentException.class,
                    () -> service.create("库", null, "a.txt", "   "));
            assertThrows(IllegalArgumentException.class,
                    () -> service.create("  ", null, "a.txt", VALID_DOC));
        }
    }

    @Nested
    @DisplayName("删除与预览")
    class 删除与预览 {

        @Test
        @DisplayName("删除：连带删题（deleteByKbId）+ 删库，单库一次调用")
        void 删除连带删题() {
            KnowledgeBase kb = new KnowledgeBase("Java 题库", null, 2, "a.txt", "hash", LocalDateTime.now());
            kb.setId(1L);
            when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
            when(questionRepository.deleteByKbId(1L)).thenReturn(2);

            service.delete(1L);

            verify(questionRepository).deleteByKbId(1L);
            verify(kbRepository).delete(kb);
        }

        @Test
        @DisplayName("删除不存在的库 → 400 提示，不动任何数据")
        void 删除不存在() {
            when(kbRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> service.delete(99L));
            verify(questionRepository, never()).deleteByKbId(anyLong());
        }

        @Test
        @DisplayName("预览：默认取前 20 道（原序）；limit 超出题目数时有多少给多少")
        void 预览截断() {
            KnowledgeBase kb = new KnowledgeBase("Java 题库", null, 3, "a.txt", "hash", LocalDateTime.now());
            kb.setId(1L);
            when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
            when(questionRepository.findByKbIdOrderBySeq(1L)).thenReturn(List.of(
                    new QuestionBankItem(1L, 1, "题一", "答案一", "分类A", QuestionBankItem.STATUS_ACTIVE, LocalDateTime.now()),
                    new QuestionBankItem(1L, 2, "题二", "答案二", "分类B", QuestionBankItem.STATUS_ACTIVE, LocalDateTime.now())));

            List<QuestionPreviewDto> preview = service.previewQuestions(1L, null);

            assertEquals(2, preview.size());
            assertEquals(1, preview.get(0).seq());
            assertEquals("题一", preview.get(0).stem());
            assertEquals("答案一", preview.get(0).referenceAnswer());
        }
    }
}
