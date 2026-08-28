package com.xiao.aiagent.services;

/**
 * LLM 结构化输出提取工具（阶段 5.2 新增）——"带 LLM 的普通函数"三兄弟（规划/评估/报告）共用。
 *
 * 为什么需要：这些环节要求 LLM 只输出 JSON，但 flash 级模型经常画蛇添足——
 *   包 ```json 围栏、前后加解释文字。直接 readValue 会炸。
 * 这里统一做两级防御：
 *   1. 剥围栏（```json ... ```）；
 *   2. 截取首个 { 或 [ 到最后一个 } 或 ]（掐掉前后解释文字）。
 *
 * 与"LLM 消费传原文、代码消费才解析"判据的关系：规划/评估/报告的输出都要被代码
 * 程序化处理（校验/落库/join），属于"代码消费"侧，所以必须解析——本类是解析前的清洗。
 */
final class LlmJson {

    private LlmJson() {
    }

    /**
     * 从 LLM 输出中提取 JSON 文本（对象或数组）。
     * 提取不到结构时原样返回（让上层 Jackson 报错走重试/降级，不在这里吞异常）。
     */
    static String extract(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.startsWith("```")) {                       // 剥 ```json 围栏
            int nl = t.indexOf('\n');
            t = nl >= 0 ? t.substring(nl + 1) : t.substring(3);
            int lastFence = t.lastIndexOf("```");
            if (lastFence >= 0) {
                t = t.substring(0, lastFence);
            }
            t = t.trim();
        }
        int start = indexOfAny(t, '{', '[');             // 截取 JSON 本体，掐掉前后解释
        int end = Math.max(t.lastIndexOf('}'), t.lastIndexOf(']'));
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }

}
