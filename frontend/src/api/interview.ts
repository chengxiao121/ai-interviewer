// 面试入场绑定 API：查会话的候选人/资料绑定关系；finish 结束面试（阶段 11）。
// 绑定本身在聊天首轮由后端创建（聊天请求带 candidateName + materialIds）。

import { getJson, postJson } from './request'
import type { InterviewSessionDto } from '@/types'

/** 面试会话绑定列表（会话管理页展示候选人用），按入场时间倒序 */
export function listInterviewSessions(): Promise<InterviewSessionDto[]> {
  return getJson<InterviewSessionDto[]>('/interview-sessions')
}

/** 查某会话的绑定；404（未入场）或其他错误 → null */
export async function getInterviewSession(sessionId: string): Promise<InterviewSessionDto | null> {
  try {
    return await getJson<InterviewSessionDto>(`/interview-sessions/${encodeURIComponent(sessionId)}`)
  } catch {
    return null
  }
}

/**
 * 结束面试（POST /api/interview-sessions/{sessionId}/finish）：状态 → FINISHED，幂等。
 * 结束后：面试官不再出题（后端每轮注入已结束提示）；评估报告解锁（报告门禁以此为准）。
 * 未入场会话 → 后端 400 透传中文提示。
 */
export function finishInterviewSession(sessionId: string): Promise<InterviewSessionDto> {
  return postJson<InterviewSessionDto>(`/interview-sessions/${encodeURIComponent(sessionId)}/finish`)
}
