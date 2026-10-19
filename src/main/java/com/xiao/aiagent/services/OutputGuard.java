package com.xiao.aiagent.services;

/**
 * 输出守卫（输出纪律优化 阶段 4 新增）——面试官回复的出口兜底，挂在流式转发前端这一环。
 *
 * 背景：2026-10-19 冒烟证明纯 prompt 禁令拦不住模型把内部备课材料复述给候选人（§1.1 #1），
 * 阶段 1（正面模板）与阶段 2（物料裁剪）是"让它不产生/没得可泄"，本类是最后一道
 * "即便还是泄露了也能机器判定"的兜底。守卫只作用于【转发给前端的流】——
 * 原始完整回答（rawAnswer）照常写回短期记忆，被截断的半截回答绝不能污染记忆（红线）。
 *
 * 判定粒度：标题行级——敏感标记词只有出现在【行首】（可带 `#`/`*`/`【` 前缀）才命中，
 * 避免题目正文自然出现"加分项"之类的误伤。跨块滑动缓冲（最近 200 字符）保证标记词
 * 即使被 SSE 分块切开也能被完整看见。
 *
 * 三种模式（配置 app.interview.output-guard.mode，Q1=A 决策默认 log）：
 *   log（默认）  命中即计数 + warn，不干预流——先上线观察命中率与误伤率；
 *   truncate     从命中位置起停止转发，前端收到"截断于泄露点之前"的回答；
 *   off          完全不启用，直通。
 *
 * 纯函数（同包直测，不起 Spring）：feed 逐块喂入，返回该块中可安全转发/截断的位置。
 */
final class OutputGuard {

    /** 敏感标记词表（§9.2 初值）。判定为标题行级，防止题目正文误伤。 */
    static final java.util.List<String> MARKER_WORDS = java.util.List.of(
            "出题意图", "追问点", "预期回答", "预期回答层级", "合格线", "加分项",
            "深渊方向", "考察重点", "预期答案");

    /** 跨块滑动缓冲长度（字符）：保证被 SSE 分块切开的标记词仍能被完整看见。 */
    static final int BUFFER_SIZE = 200;

    /** 标题行前缀字符集合：标记词必须紧跟在这些前缀之后（行首）才算命中。空格也算标题装饰。 */
    private static final String HEAD_PREFIXES = "#*【- ";

    /** 守卫模式。 */
    enum Mode { LOG, TRUNCATE, OFF }

    private Mode mode;
    private final StringBuilder buffer = new StringBuilder();
    /** 累计命中次数（log 模式观测命中率用）。 */
    private int hitCount = 0;

    OutputGuard(Mode mode) {
        this.mode = mode;
    }

    int hitCount() {
        return hitCount;
    }

    /**
     * 喂入一块文本，返回该块应转发给前端的部分（null 表示整块都该拦下）。
     *
     * off 模式：原样返回整块，不做任何缓冲/判定。
     * log 模式：整块原样返回（只记录命中，不干预流）。
     * truncate 模式：返回命中位置之前的可转发前缀；一旦命中，后续调用一律返回 null（已截断）。
     */
    String feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return chunk;
        }
        if (mode == Mode.OFF) {
            return chunk;
        }
        // 命中后（truncate 已截断）不再转发任何内容，也不再把新块计入缓冲
        if (isTruncated()) {
            return null;
        }

        buffer.append(chunk);
        if (buffer.length() > BUFFER_SIZE) {
            buffer.delete(0, buffer.length() - BUFFER_SIZE);   // 只保留最近 200 字符
        }

        int hitPos = findHitPosition(buffer.toString());
        if (hitPos < 0) {
            return mode == Mode.TRUNCATE ? chunk : chunk;      // 未命中：log 与 truncate 均整块放行
        }

        hitCount++;
        // hitPos 是缓冲内的命中行首位置，需换算成"本块内"的相对位置
        int chunkStart = buffer.length() - chunk.length();
        int rel = hitPos - chunkStart;

        if (mode == Mode.LOG) {
            return chunk;   // 只记录，不干预流
        }
        // TRUNCATE：命中在本块之前（缓冲里的历史内容）→ 本块整体截断；在本块内 → 截到命中点
        if (rel < 0) {
            return null;
        }
        String prefix = chunk.substring(0, rel);
        return prefix.isEmpty() ? null : prefix;
    }

    /** 是否已进入"截断后"状态（truncate 命中过）。 */
    boolean isTruncated() {
        return mode == Mode.TRUNCATE && hitCount > 0;
    }

    /**
     * 在滑动缓冲文本里找首个"标题行级敏感标记"的行首位置。
     * 命中条件：标记词前面的字符（回溯到行首）全部是标题装饰字符（#、*、【、-、空格）——
     * 即标记词只出现在"### 追问点""* 预期回答""【考察重点】"这类标题行里；
     * 若标记词前面夹了普通文字（中文/字母/数字），说明它嵌在题目正文里 → 不命中。
     * 返回命中标记词在 buffer 里的下标；未命中返回 -1。
     */
    static int findHitPosition(String buffer) {
        for (String word : MARKER_WORDS) {
            int from = 0;
            while (true) {
                int idx = buffer.indexOf(word, from);
                if (idx < 0) {
                    break;
                }
                if (precededByHeadPrefixes(buffer, idx)) {
                    return idx;
                }
                from = idx + word.length();
            }
        }
        return -1;
    }

    /**
     * 标记词之前（回溯到行首）是否只有标题装饰字符（#、*、【、-、空格）。
     * 从标记词往前扫：允许越过标题装饰字符与空格，直到碰到换行（=该行行首）或文本开头 → 合法标题行；
     * 若中途碰到普通文字字符（中文/字母/数字等），说明标记词嵌在正文里 → 不是标题行。
     */
    private static boolean precededByHeadPrefixes(String s, int idx) {
        for (int i = idx - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (HEAD_PREFIXES.indexOf(c) >= 0) {
                continue;   // 越过标题装饰字符与空格继续往前看
            }
            // 碰到第一个非装饰字符：必须是换行（行首），否则说明夹了普通文字 → 不是标题行
            return c == '\n' || c == '\r';
        }
        return true;   // 一直回溯到文本开头都是装饰字符 → 标题行
    }

}
