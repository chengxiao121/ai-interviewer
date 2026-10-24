package com.xiao.aiagent.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库文档解析器（阶段 12 新增，决策 4：题目用解析，不用 LLM 生成）。
 *
 * 题库文档格式（与内置示例 java-interview-questions.txt 同构）：
 *   # 一级标题        —— 文档名，忽略
 *   ## 二级标题       —— 分类（category），作用到下一个 ## 之前的所有题目
 *   【考点】题目正文   —— 一道题的开始
 *   【参考答案】答案   —— 该题的参考答案
 *   空行              —— 一道题的结束
 *
 * 解析规则（对应计划文档风险 7 的边界约定）：
 *   - 成对提取：【考点】后必须能找到【参考答案】才成题；只有考点没有答案的块
 *     【跳过并计数】（skippedCount），不产出残缺题目；
 *   - CRLF / CR 统一先归一为 \n；
 *   - 题目/答案支持跨行：从标记行到下一个结构边界之间的普通文本行拼进当前段；
 *   - 解析是纯函数：不抛异常、不碰 Spring/IO——空文档/null 返回 0 题 0 跳过，
 *     "一道都没解出来"由调用方（QuestionBankService）决定是否拒绝建库。
 *
 * 为什么不用 LLM 生成：题库文档本身就是题目，按块解析零成本、结果确定、当场可验证；
 * 参考项目用 LLM 是因为它的文档是整本教材（无题目边界），我们没有这道工序。
 */
public final class QuestionParser {

    private QuestionParser() {
    }

    /** 解析出的一道题（纯数据，落库前形态；seq 由调用方按解析顺序编号） */
    public record ParsedQuestion(String stem, String referenceAnswer, String category) {
    }

    /** 解析结果：题目列表 + 统计（skippedCount 让前端/日志能自查"少了几道题"） */
    public record ParseResult(List<ParsedQuestion> questions, int skippedCount) {
        public int parsedCount() {
            return questions.size();
        }
    }

    private static final String TOPIC_MARKER = "【考点】";
    private static final String ANSWER_MARKER = "【参考答案】";

    /**
     * 解析题库文档全文。
     *
     * @param text 文档原文（txt/md 纯文本；null 或空白返回 0 题）
     * @return 题目列表（按文档原序）与跳过计数
     */
    public static ParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            return new ParseResult(List.of(), 0);
        }

        Cursor c = new Cursor();
        for (String rawLine : text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                // 空行 = 一道题结束
                c.flush();
            } else if (line.startsWith("##")) {
                // 二级标题 = 分类切换（先收尾，防"标题前没空行"的文档把题截断）
                c.flush();
                String heading = line.replaceFirst("^#+\\s*", "").strip();
                c.category = heading.isEmpty() ? null : heading;
            } else if (line.startsWith("#")) {
                // 一级标题 = 文档名，忽略（同样先收尾）
                c.flush();
            } else if (line.startsWith(TOPIC_MARKER)) {
                // 新题开始（上一题若没收尾，先按收尾规则处理）
                c.flush();
                c.stem = new StringBuilder(line.substring(TOPIC_MARKER.length()).strip());
            } else if (line.startsWith(ANSWER_MARKER)) {
                if (c.stem == null) {
                    // 孤立的【参考答案】（不属于任何题）：跳过计数
                    c.skipped++;
                } else {
                    c.answer = new StringBuilder(line.substring(ANSWER_MARKER.length()).strip());
                }
            } else if (c.stem != null && c.answer == null) {
                // 普通文本行：拼进题干（题干跨行）
                c.stem.append('\n').append(line);
            } else if (c.stem != null) {
                // 普通文本行：拼进参考答案（答案跨行）
                c.answer.append('\n').append(line);
            }
            // 不在题目块中的普通文本行（文档说明文字）忽略
        }
        c.flush(); // 文件末尾没有空行收尾的最后一题

        return new ParseResult(List.copyOf(c.questions), c.skipped);
    }

    /**
     * 解析游标：题目块的累积状态 + 收尾规则集中一处（"成对才成题，缺答案跳过计数"）。
     * 收尾在四个边界都会发生（空行 / ## / # / 新【考点】），不收进一个方法会复制四遍。
     */
    private static final class Cursor {
        StringBuilder stem;                       // 当前题干（null = 不在题目块中）
        StringBuilder answer;                     // 当前参考答案（null = 还没见到答案标记）
        String category;                          // 当前分类（最近的 ## 标题；没有则 null）
        final List<ParsedQuestion> questions = new ArrayList<>();
        int skipped;

        void flush() {
            if (stem != null) {
                String stemText = stem.toString().strip();
                // 成对才成题：有干有案且题干非空；缺答案或题干为空的块都跳过计数（不产残缺题）
                if (answer != null && !answer.isEmpty() && !stemText.isEmpty()) {
                    questions.add(new ParsedQuestion(stemText, answer.toString().strip(), category));
                } else {
                    skipped++;
                }
            }
            stem = null;
            answer = null;
        }
    }
}
