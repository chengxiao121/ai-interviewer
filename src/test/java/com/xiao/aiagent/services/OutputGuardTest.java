package com.xiao.aiagent.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OutputGuard 纯函数单测（输出纪律优化 阶段 4）。
 *
 * 覆盖三类核心契约：
 *   ① 行首级判定——只有标题行（行首可带 #、*、【 等前缀）才命中，题目正文自然出现的敏感词不误伤；
 *   ② 跨块滑动缓冲——标记词被 SSE 分块切开仍能命中；
 *   ③ 三种模式的处置差异——log 只计数不干预、truncate 命中截断、off 直通。
 */
@DisplayName("OutputGuard：面试官回复出口守卫")
class OutputGuardTest {

    @Nested
    @DisplayName("行首级判定（防误伤）")
    class LineStartDetection {

        @Test
        void 标题行命中() {
            // "追问点" 起始下标 4（### + 空格 占 0~3），返回标记词位置而非行首
            assertEquals(4, OutputGuard.findHitPosition("### 追问点：如何决策"));
        }

        @Test
        void 带井号前缀命中() {
            String buf = "前面的引导文字\n## 出题意图\n正文";
            assertTrue(OutputGuard.findHitPosition(buf) > 0);
        }

        @Test
        void 带星号前缀命中() {
            assertTrue(OutputGuard.findHitPosition("* 预期回答层级：") >= 0);
        }

        @Test
        void 带方括号前缀命中() {
            assertTrue(OutputGuard.findHitPosition("【考察重点】") >= 0);
        }

        @Test
        void 题目正文里的敏感词不误伤() {
            // "加分项"出现在句子中间而非行首标题 → 不命中
            assertEquals(-1, OutputGuard.findHitPosition("请说说这段代码里有哪些加分项值得保留？"));
        }

        @Test
        void 行中出现的敏感词不误伤() {
            assertEquals(-1, OutputGuard.findHitPosition("这道题的加分项是边界处理，你来分析"));
        }
    }

    @Nested
    @DisplayName("跨块滑动缓冲")
    class CrossChunkBuffer {

        @Test
        void 标记词被分块切开仍命中() {
            OutputGuard g = new OutputGuard(OutputGuard.Mode.TRUNCATE);
            g.feed("### 追");
            String out = g.feed("问点：如何决策？");
            // "问点"这一块里，命中位置在块首（前面的"### 追"在缓冲里）
            assertNull(out, "命中后该块应被截断（前缀为空）");
            assertEquals(1, g.hitCount());
        }

        @Test
        void 命中前的内容正常放行() {
            OutputGuard g = new OutputGuard(OutputGuard.Mode.TRUNCATE);
            String first = "先给一句点评\n### 第 2 题：缓存一致性";
            assertEquals(first, g.feed(first), "未命中前整块放行");
            assertEquals(0, g.hitCount());
        }
    }

    @Nested
    @DisplayName("三种模式处置")
    class ModeBehavior {

        @Test
        void off模式直通_不缓冲不判定() {
            OutputGuard g = new OutputGuard(OutputGuard.Mode.OFF);
            String chunk = "### 出题意图：考察缓存";
            assertSame(chunk, g.feed(chunk));
            assertEquals(0, g.hitCount());
        }

        @Test
        void log模式命中计数但不截断() {
            OutputGuard g = new OutputGuard(OutputGuard.Mode.LOG);
            String chunk = "### 追问点：如何决策";
            assertEquals(chunk, g.feed(chunk), "log 模式不干预流，整块放行");
            assertEquals(1, g.hitCount());
        }

        @Test
        void truncate模式命中后不再转发() {
            OutputGuard g = new OutputGuard(OutputGuard.Mode.TRUNCATE);
            // 命中"出题意图"前的内容（含标题前缀 ### 与空格）可转发，标记词起截断
            String out = g.feed("引导一句\n### 出题意图：考察");
            assertEquals("引导一句\n### ", out, "截到标记词之前（含标题前缀）");
            assertNull(g.feed("后续内容"), "命中后不再转发");
            assertTrue(g.isTruncated());
        }

        @Test
        void truncate模式未命中整块放行() {
            OutputGuard g = new OutputGuard(OutputGuard.Mode.TRUNCATE);
            String chunk = "这是正常回答，没有任何敏感结构。";
            assertEquals(chunk, g.feed(chunk));
        }

        @Test
        void 空块直通() {
            OutputGuard g = new OutputGuard(OutputGuard.Mode.TRUNCATE);
            assertNull(g.feed(null));
            assertSame("", g.feed(""));
        }
    }

}
