package com.xiao.aiagent.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LlmJson.extract 纯函数单测（P1 测试补齐）。
 *
 * 覆盖两级防御的真实输入形态：
 *   ① 剥 ```json 围栏（flash 模型最常见的画蛇添足）
 *   ② 截取首个 { / [ 到最后一个 } / ]（掐掉前后解释文字）
 * 以及"提取不到结构时原样返回、让上层 Jackson 报错"的约定。
 */
@DisplayName("LlmJson.extract：LLM 输出中提取 JSON")
class LlmJsonTest {

    @Nested
    @DisplayName("裸 JSON（无围栏、无解释文字）")
    class BareJson {
        @Test
        void 对象原样返回() {
            String in = "{\"isAnswer\":true,\"score\":7.5}";
            assertEquals(in, LlmJson.extract(in));
        }

        @Test
        void 数组原样返回() {
            String in = "[{\"topic\":\"线程池\"},{\"topic\":\"JVM\"}]";
            assertEquals(in, LlmJson.extract(in));
        }

        @Test
        void 带首尾空白的对象() {
            assertEquals("{\"a\":1}", LlmJson.extract("  \n {\"a\":1} \n "));
        }
    }

    @Nested
    @DisplayName("围栏防御")
    class FenceStripping {
        @Test
        void json代码块围栏() {
            String in = "```json\n{\"isAnswer\":true}\n```";
            assertEquals("{\"isAnswer\":true}", LlmJson.extract(in));
        }

        @Test
        void 无语言标注的围栏() {
            String in = "```\n{\"a\":1}\n```";
            assertEquals("{\"a\":1}", LlmJson.extract(in));
        }

        @Test
        void 围栏无闭合() {
            // 模型输出被截断：有开栏没闭栏——剥掉开栏后靠 { } 截取兜底
            String in = "```json\n{\"a\":1}";
            assertEquals("{\"a\":1}", LlmJson.extract(in));
        }

        @Test
        void 围栏内是数组() {
            String in = "```json\n[1,2,3]\n```";
            assertEquals("[1,2,3]", LlmJson.extract(in));
        }
    }

    @Nested
    @DisplayName("解释文字防御")
    class ProseStripping {
        @Test
        void 前置解释文字() {
            String in = "以下是评估结果：{\"score\":8}";
            assertEquals("{\"score\":8}", LlmJson.extract(in));
        }

        @Test
        void 前后都有解释文字() {
            String in = "好的，评估如下 {\"score\":8} 希望对你有帮助。";
            assertEquals("{\"score\":8}", LlmJson.extract(in));
        }

        @Test
        void 围栏加解释文字双重防御() {
            String in = "评估结果如下：\n```json\n{\"topic\":\"JVM\",\"score\":5}\n```\n以上。";
            assertEquals("{\"topic\":\"JVM\",\"score\":5}", LlmJson.extract(in));
        }
    }

    @Nested
    @DisplayName("异常输入（提取不到结构 → 原样返回，让上层 Jackson 报错）")
    class DegenerateInput {
        @Test
        void null返回空串() {
            assertEquals("", LlmJson.extract(null));
        }

        @Test
        void 空串原样返回() {
            assertEquals("", LlmJson.extract(""));
        }

        @Test
        void 纯文本无结构原样返回() {
            String in = "抱歉，我无法回答。";
            assertEquals(in, LlmJson.extract(in));
        }
    }

    @Nested
    @DisplayName("边界：字符串值里含 { } 或围栏标记")
    class TrickyContent {
        @Test
        void 字符串值里的花括号不干扰截取() {
            // 截取策略基于"首个 { 到最后一个 }"，值内花括号天然被包含，不受影响
            String in = "{\"text\":\"代码块 {a} 示例\"}";
            assertEquals(in, LlmJson.extract(in));
        }
    }
}
