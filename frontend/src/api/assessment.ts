import { getJson } from './request'
import type { AssessmentReport } from '@/types'

/**
 * 获取面试评估报告（GET /api/assessment/{sessionId}/report）。
 *
 * 后端 ensure 模式：首次调用生成并落库（含一次叙事 LLM 调用，可能数秒），
 * 之后直接读库返回快照（秒回）。报告是生成时刻的快照——跨会话薄弱点段落
 * 不会随后续新面试自动更新。
 *
 * @param sessionId   会话 id（报告归属键）
 * @param candidateId 候选人 id（跨会话薄弱点段落的数据维度；不传走后端默认口径）
 * @param force       true = 忽略缓存重新生成（version+1 覆盖）；页面未提供入口，仅保留通道
 */
export function getReport(sessionId: string, candidateId?: string, force = false): Promise<AssessmentReport> {
  const params = new URLSearchParams()
  if (candidateId) params.set('candidateId', candidateId)
  if (force) params.set('force', 'true')
  const qs = params.toString()
  return getJson<AssessmentReport>(
    `/assessment/${encodeURIComponent(sessionId)}/report${qs ? `?${qs}` : ''}`,
  )
}
