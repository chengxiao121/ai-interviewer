package com.xiao.aiagent.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 面试官物料裁剪器（输出纪律优化 阶段 2 新增）——注入面试官上下文前的敏感字段剥离。
 *
 * 背景：【面试官物料】的 factsJson 里带 verifyQuestion（"建议的验证问题"，泄露即泄题）。
 * 2026-10-19 冒烟实测证明纯 prompt 禁令拦不住模型把备课材料复述给候选人（§1.1 问题 1），
 * 结构性治理第一刀就是"别让它进上下文"——本类在消费端（loadCodeFacts）注入前裁剪。
 *
 * 决策口径（实施计划 §9.1 Q2=A）：只剥 verifyQuestion，保留 probingAngle——
 * probingAngle 是"值得深挖的方向"，本身是考察内容的一部分、出题深度的主要来源，
 * 残余泄露风险由阶段 1 正面模板 + 阶段 4 输出守卫兜底。
 *
 * 降级哲学（与"题库检索不到就跳过"同一原则）：解析失败不让面试阻塞——
 * 原样返回 factsJson + warn 日志。坏 JSON 对 LLM 只是"一段长得像 JSON 的文字"，
 * 照样能出题；裁剪失败的最坏结果是泄露风险回到裁剪前水平，不是面试挂掉。
 *
 * 纯函数（同包直测，不起 Spring）：
 *   sanitize(含敏感键 JSON)   → 剥离后重新序列化
 *   sanitize(不含敏感键 JSON) → 原样返回（零改动，不重新序列化，避免无谓的格式抖动）
 *   sanitize(非法 JSON/null)  → 原样返回 + warn
 */
final class MaterialSanitizer {

    private static final Logger log = LoggerFactory.getLogger(MaterialSanitizer.class);

    /** 敏感键集合（初值仅 verifyQuestion，见类注释的 Q2=A 决策；词表扩张只改这一处）。 */
    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of("verifyQuestion");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MaterialSanitizer() {
    }

    /**
     * 递归移除 factsJson 里的敏感键后返回安全文本。
     * 树里没有任何敏感键时返回原字符串（保持原格式，不做等价重序列化）；
     * 解析失败原样返回（降级哲学，warn 但不抛）。
     */
    static String sanitize(String factsJson) {
        if (factsJson == null || factsJson.isBlank()) {
            return factsJson;
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(factsJson);
        } catch (Exception e) {
            // 降级：坏 JSON 原样放行，面试不阻塞（与 LlmJson "让上层报错" 不同——
            // 这里没有上层重试，吞掉是为了保面试可用性）
            log.warn("factsJson 解析失败，物料按原文注入（裁剪未生效）：{}", e.getMessage());
            return factsJson;
        }
        if (root == null || !root.isContainerNode() || !removeSensitive(root)) {
            return factsJson; // 标量根节点 / 没扫到敏感键 → 原样
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // 理论不可达（readTree 成功的树一定能序列化），防御性降级保持同一契约
            log.warn("裁剪后的 factsJson 序列化失败，物料按原文注入：{}", e.getMessage());
            return factsJson;
        }
    }

    /**
     * 深度优先移除敏感键。返回树是否被改动——用于"没改就不重新序列化"的判定。
     */
    private static boolean removeSensitive(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode obj) {
            var it = obj.fields();
            while (it.hasNext()) {
                var entry = it.next();
                if (SENSITIVE_KEYS.contains(entry.getKey())) {
                    it.remove();
                    changed = true;
                } else {
                    changed |= removeSensitive(entry.getValue());
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                changed |= removeSensitive(child);
            }
        }
        return changed;
    }

}
