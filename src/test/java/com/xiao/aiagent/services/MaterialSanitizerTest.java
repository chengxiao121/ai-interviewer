package com.xiao.aiagent.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MaterialSanitizer.sanitize 纯函数单测（输出纪律优化 阶段 2）。
 *
 * 覆盖三类契约（与实施计划 §五阶段 2 一一对应）：
 *   ① 含敏感键的嵌套 JSON 剥离干净（verifyQuestion 泄露即泄题，这是主路径）；
 *   ② 无敏感键的 JSON 原样返回（assertSame：连重新序列化都不做，格式零抖动）；
 *   ③ 非法 JSON 降级原样返回（降级哲学：裁剪失败不阻塞面试）。
 * 以及 Q2=A 决策的回归锚点：probingAngle 必须保留（它是出题深度的主要来源）。
 */
@DisplayName("MaterialSanitizer.sanitize：面试官物料敏感字段剥离")
class MaterialSanitizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("敏感键剥离（主路径）")
    class StripSensitiveKeys {

        @Test
        void 嵌套数组对象里的verifyQuestion被剥掉_兄弟字段保留() throws Exception {
            // 还原 ResumeAnalyzerAgent 产出的真实结构：weaknessCandidates 数组内嵌对象
            String in = "{\"weaknessCandidates\":["
                    + "{\"aspect\":\"Redis 持久化\",\"reason\":\"简历写了但无佐证\",\"verifyQuestion\":\"RDB 与 AOF 怎么选\"}"
                    + "]}";
            String out = MaterialSanitizer.sanitize(in);
            JsonNode node = MAPPER.readTree(out);
            JsonNode wc = node.get("weaknessCandidates").get(0);
            assertEquals("Redis 持久化", wc.get("aspect").asText(), "兄弟字段 aspect 应保留");
            assertEquals("简历写了但无佐证", wc.get("reason").asText(), "兄弟字段 reason 应保留");
            assertNull(wc.get("verifyQuestion"), "敏感键 verifyQuestion 必须被剥离");
            assertFalse(out.contains("RDB 与 AOF 怎么选"), "敏感键的值（现成问题）不能残留在输出文本里");
        }

        @Test
        void 多处出现全部剥掉() throws Exception {
            String in = "{\"verifyQuestion\":\"顶层问题\",\"list\":["
                    + "{\"verifyQuestion\":\"嵌套问题1\"},{\"verifyQuestion\":\"嵌套问题2\",\"keep\":\"留\"}]}";
            JsonNode node = MAPPER.readTree(MaterialSanitizer.sanitize(in));
            assertFalse(node.has("verifyQuestion"), "顶层应剥离");
            assertFalse(node.get("list").get(0).has("verifyQuestion"), "数组第 1 项应剥离");
            assertFalse(node.get("list").get(1).has("verifyQuestion"), "数组第 2 项应剥离");
            assertEquals("留", node.get("list").get(1).get("keep").asText(), "非敏感字段保留");
        }

        @Test
        void 根是数组也能递归() throws Exception {
            String in = "[{\"verifyQuestion\":\"q\"},{\"a\":1}]";
            JsonNode node = MAPPER.readTree(MaterialSanitizer.sanitize(in));
            assertFalse(node.get(0).has("verifyQuestion"));
            assertEquals(1, node.get(1).get("a").asInt());
        }

        @Test
        void 敏感键值为对象或数组同样整棵移除() throws Exception {
            // 键的值不是叶子而是子树——整个子树都该拿掉，不留下空壳
            String in = "{\"verifyQuestion\":{\"hint\":[\"a\",\"b\"]},\"top\":true}";
            JsonNode node = MAPPER.readTree(MaterialSanitizer.sanitize(in));
            assertFalse(node.has("verifyQuestion"));
            assertTrue(node.get("top").asBoolean());
        }
    }

    @Nested
    @DisplayName("保留与原样（不误伤 + 零抖动）")
    class KeepIntact {

        @Test
        void probingAngle必须保留() {
            // Q2=A 决策的回归锚点：probingAngle 是出题深度的主要来源，误剥会让出题变浅
            String in = "{\"projectHighlights\":[{\"project\":\"交易平仓\",\"probingAngle\":\"追问幂等怎么做的\"}]}";
            String out = MaterialSanitizer.sanitize(in);
            assertTrue(out.contains("追问幂等怎么做的"), "probingAngle 的值必须原样保留");
        }

        @Test
        void 无敏感键的JSON原样返回_不重新序列化() {
            String in = "{\n  \"candidateOverview\": \"3 年 Java 后端\",\n  \"skillProfile\": [\"Java\", \"Redis\"]\n}";
            assertSame(in, MaterialSanitizer.sanitize(in), "没扫到敏感键应返回原字符串（含原格式缩进换行）");
        }

        @Test
        void null与空白原样返回() {
            assertNull(MaterialSanitizer.sanitize(null));
            assertEquals("", MaterialSanitizer.sanitize(""));
            assertEquals("   ", MaterialSanitizer.sanitize("   "));
        }

        @Test
        void 标量根节点原样返回() {
            // factsJson 理论上是容器，但标量进来不该炸也不该被改
            String in = "\"just a string\"";
            assertSame(in, MaterialSanitizer.sanitize(in));
        }
    }

    @Nested
    @DisplayName("非法 JSON 降级（裁剪失败不阻塞面试）")
    class DegradeOnBadJson {

        @Test
        void 坏JSON原样返回_不抛异常() {
            String in = "{不是合法JSON";
            assertSame(in, MaterialSanitizer.sanitize(in), "解析失败应原样放行，让 LLM 当普通文本读");
        }

        @Test
        void 空对象原样返回() {
            String in = "{}";
            assertSame(in, MaterialSanitizer.sanitize(in), "空对象无敏感键，原样");
        }
    }

}
