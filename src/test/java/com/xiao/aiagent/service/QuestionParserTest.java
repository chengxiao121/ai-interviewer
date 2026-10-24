package com.xiao.aiagent.service;

import com.xiao.aiagent.service.QuestionParser.ParsedQuestion;
import com.xiao.aiagent.service.QuestionParser.ParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuestionParser 纯函数单测（阶段 12，计划文档风险 7：解析质量边界）。
 *
 * 解析是题库入库的唯一闸门——解析出残缺题目会一路污染组卷、出题、评分、报告，
 * 所以这里锁的是最严格的口径：成对提取、缺答案跳过计数、跨行可拼、CRLF 兼容。
 */
@DisplayName("QuestionParser.parse：题库文档解析")
class QuestionParserTest {

    @Nested
    @DisplayName("正常格式")
    class 正常格式 {

        @Test
        @DisplayName("标准文档：一级标题忽略、二级标题成分类、成对提取、按原序编号")
        void 标准文档() {
            String doc = """
                    # Java 后端面试题库

                    ## 集合

                    【考点】HashMap 的底层数据结构是什么？
                    【参考答案】数组 + 链表 + 红黑树。

                    【考点】ArrayList 和 LinkedList 的区别？
                    【参考答案】动态数组 vs 双向链表。

                    ## 并发

                    【考点】volatile 能保证原子性吗？
                    【参考答案】不能，只保证可见性和有序性。
                    """;

            ParseResult result = QuestionParser.parse(doc);

            assertEquals(3, result.parsedCount());
            assertEquals(0, result.skippedCount());
            ParsedQuestion first = result.questions().get(0);
            assertEquals("HashMap 的底层数据结构是什么？", first.stem());
            assertEquals("数组 + 链表 + 红黑树。", first.referenceAnswer());
            assertEquals("集合", first.category());
            assertEquals("并发", result.questions().get(2).category());
        }

        @Test
        @DisplayName("题目与答案跨多行 → 拼接为完整段落（无半道题）")
        void 跨行拼接() {
            String doc = """
                    【考点】线程池的核心参数有哪些？
                    请从七个参数角度回答。
                    【参考答案】corePoolSize、maximumPoolSize、
                    keepAliveTime、workQueue、threadFactory、
                    handler 拒绝策略。
                    """;

            ParseResult result = QuestionParser.parse(doc);

            assertEquals(1, result.parsedCount());
            ParsedQuestion q = result.questions().get(0);
            assertEquals("线程池的核心参数有哪些？\n请从七个参数角度回答。", q.stem());
            assertEquals("corePoolSize、maximumPoolSize、\nkeepAliveTime、workQueue、threadFactory、\nhandler 拒绝策略。", q.referenceAnswer());
        }

        @Test
        @DisplayName("文件末尾最后一题没有空行收尾 → 仍能解析")
        void 末尾无空行() {
            String doc = "【考点】第一题\n【参考答案】答案一\n\n【考点】第二题\n【参考答案】答案二";

            ParseResult result = QuestionParser.parse(doc);

            assertEquals(2, result.parsedCount());
            assertEquals("第二题", result.questions().get(1).stem());
        }

        @Test
        @DisplayName("结构边界前没有空行（## 直接跟在答案后）→ 前题收尾不截断")
        void 标题前无空行() {
            String doc = """
                    【考点】第一题
                    【参考答案】答案一
                    ## 下一章
                    【考点】第二题
                    【参考答案】答案二
                    """;

            ParseResult result = QuestionParser.parse(doc);

            assertEquals(2, result.parsedCount());
            assertEquals("下一章", result.questions().get(1).category());
        }
    }

    @Nested
    @DisplayName("边界与异常输入")
    class 边界与异常输入 {

        @Test
        @DisplayName("缺【参考答案】的题 → 跳过并计数，不产出残缺题目")
        void 缺参考答案跳过() {
            String doc = """
                    【考点】完整的题
                    【参考答案】答案完整。

                    【考点】缺答案的题

                    【考点】第二道完整的题
                    【参考答案】答案二。
                    """;

            ParseResult result = QuestionParser.parse(doc);

            assertEquals(2, result.parsedCount());
            assertEquals(1, result.skippedCount());
            assertEquals("完整的题", result.questions().get(0).stem());
        }

        @Test
        @DisplayName("无任何 ## 分类标题 → category 为 null")
        void 无分类标题() {
            String doc = "【考点】题一\n【参考答案】答案一\n\n【考点】题二\n【参考答案】答案二\n";

            ParseResult result = QuestionParser.parse(doc);

            assertEquals(2, result.parsedCount());
            assertNull(result.questions().get(0).category());
            assertNull(result.questions().get(1).category());
        }

        @Test
        @DisplayName("空文件 / 空白文件 / null → 0 题 0 跳过，不抛异常")
        void 空文件() {
            assertEquals(0, QuestionParser.parse("").parsedCount());
            assertEquals(0, QuestionParser.parse("   \n\n  ").parsedCount());
            ParseResult nullResult = QuestionParser.parse(null);
            assertEquals(List.of(), nullResult.questions());
            assertEquals(0, nullResult.skippedCount());
        }

        @Test
        @DisplayName("CRLF / CR 换行 → 与 LF 等价解析")
        void 换行符归一() {
            String doc = "【考点】CRLF 的题\r\n【参考答案】CRLF 的答案\r\n\r\n【考点】CR 的题\r【参考答案】CR 的答案";

            ParseResult result = QuestionParser.parse(doc);

            assertEquals(2, result.parsedCount());
            assertEquals(0, result.skippedCount());
            assertEquals("CRLF 的题", result.questions().get(0).stem());
            assertEquals("CR 的答案", result.questions().get(1).referenceAnswer());
        }

        @Test
        @DisplayName("孤立的【参考答案】（无考点归属）→ 跳过计数")
        void 孤立参考答案() {
            String doc = """
                    【参考答案】没有考点的孤立答案

                    【考点】正常的题
                    【参考答案】答案。
                    """;

            ParseResult result = QuestionParser.parse(doc);

            assertEquals(1, result.parsedCount());
            assertEquals(1, result.skippedCount());
        }

        @Test
        @DisplayName("【考点】标记后为空 + 无答案 → 计为跳过（不产空题）")
        void 空考点跳过() {
            String doc = "【考点】\n【参考答案】答案\n";

            ParseResult result = QuestionParser.parse(doc);

            // stem 非空（"答案"进了题干）且无独立答案 → 跳过；不产出"题干=答案"的错位题
            assertEquals(0, result.parsedCount());
            assertEquals(1, result.skippedCount());
            assertTrue(result.questions().isEmpty());
        }

        @Test
        @DisplayName("非题库格式文档（纯说明文字）→ 0 题（由调用方拒绝建库）")
        void 非题库格式() {
            ParseResult result = QuestionParser.parse("# 使用说明\n\n这是一份普通文档，没有任何题目标记。");

            assertEquals(0, result.parsedCount());
        }
    }
}
