package com.xiao.aiagent.dto;

import java.util.List;

/**
 * 卷子 DTO（阶段 12 新增）——入场时一次性组好的题目快照，两个用途：
 *   ① 序列化进 interview_plan.paper_json（数据库里的"本场考什么"真源，题库后续增删不影响已开场的面试）；
 *   ② 阶段 5 面试官注入（【本场卷子】）与前端预览共用同一结构。
 *
 * 与面试计划 topics_json 的分工：paper_json 是"题目原文 + 参考答案"（念给候选人听、
 * 评分对照用）；topics_json 是"考点短名词表"（评估封闭词表 + 报告覆盖矩阵基准）。
 * 两者由 PaperComposeService 从同一批抽中的题目同步生成。
 */
public record PaperDto(String kbName, List<PaperQuestion> questions) {

    /**
     * 卷子里的一道题。
     * no：卷内顺序（1..N，出题顺序即此顺序，评估按题号可对回卷子）；
     * questionId：溯源 question_bank.id（将来"错题本/再刷一轮"要按它回查原题）。
     */
    public record PaperQuestion(int no, String stem, String referenceAnswer,
                                String category, Long questionId) {
    }
}
