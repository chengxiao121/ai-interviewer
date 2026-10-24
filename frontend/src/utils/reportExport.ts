import type { AssessmentReport, ReportCoverageItem } from '@/types'

/**
 * 报告导出纯函数：评估报告 JSON → Markdown 文本。
 *
 * 为什么放 utils 且写成纯函数：转换规则可单测（纯字符串进出，无 DOM/网络依赖）；
 * 后端只存结构化 JSON（渲染与导出共用一份数据），Markdown 是导出时的呈现格式。
 */

/** 单个考点 → Markdown 小节（覆盖项与计划外项同构，复用） */
function coverageToMarkdown(item: ReportCoverageItem): string {
  const lines: string[] = []
  lines.push(`### ${item.topic}`)
  lines.push('')
  lines.push(`- 题数：${item.questionCount}`)
  lines.push(`- 平均分：${item.avgScore} / 最低分：${item.minScore}`)
  for (const ev of item.evidences ?? []) {
    lines.push('')
    lines.push(`> **Q**：${ev.question}`)
    lines.push('>')
    lines.push(`> **A（节选）**：${ev.answerExcerpt}`)
    lines.push('>')
    lines.push(`> **得分**：${ev.score}｜**点评**：${ev.feedback}`)
  }
  lines.push('')
  return lines.join('\n')
}

/** 报告 JSON → Markdown 全文（结构对齐 AssessmentReportService.generate，字段按 optional 兜底） */
export function reportToMarkdown(report: AssessmentReport): string {
  const out: string[] = []
  out.push(`# 面试评估报告`)
  out.push('')
  out.push(`- 会话：\`${report.sessionId}\``)
  out.push(`- 候选人：${report.candidateId}`)
  out.push(`- 生成时间：${report.generatedAt}`)
  out.push('')

  const c = report.conclusion
  if (c) {
    out.push('## 总体结论')
    out.push('')
    out.push(c.overallSummary || '（无）')
    out.push('')
    out.push('## 匹配度结论')
    out.push('')
    out.push(c.matchConclusion || '（无）')
    out.push('')
    const recs = c.recommendations ?? []
    if (recs.length > 0) {
      out.push('## 提升建议')
      out.push('')
      for (const r of recs) out.push(`- ${r}`)
      out.push('')
    }
  }

  const coverage = report.coverage ?? []
  if (coverage.length > 0) {
    out.push('## 覆盖矩阵（计划考点）')
    out.push('')
    for (const item of coverage) out.push(coverageToMarkdown(item))
  }

  const uncovered = report.uncoveredTopics ?? []
  if (uncovered.length > 0) {
    out.push('## 未覆盖考点')
    out.push('')
    for (const t of uncovered) out.push(`- ${t}`)
    out.push('')
  }

  const outOfPlan = report.outOfPlanTopics ?? []
  if (outOfPlan.length > 0) {
    out.push('## 计划外考点')
    out.push('')
    for (const item of outOfPlan) out.push(coverageToMarkdown(item))
  }

  if (report.crossSessionWeakness) {
    out.push('## 跨会话薄弱点回顾')
    out.push('')
    out.push('```')
    out.push(report.crossSessionWeakness)
    out.push('```')
    out.push('')
  }

  return out.join('\n')
}

/** 触发浏览器下载（Blob + 临时 <a>，纯浏览器端零依赖） */
export function downloadTextFile(content: string, fileName: string, mime = 'text/markdown'): void {
  const blob = new Blob([content], { type: `${mime};charset=utf-8` })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/** 导出文件名：报告-{sessionId 前 8 位}.md */
export function reportFileName(report: AssessmentReport, ext: string): string {
  return `报告-${report.sessionId.slice(0, 8)}.${ext}`
}
