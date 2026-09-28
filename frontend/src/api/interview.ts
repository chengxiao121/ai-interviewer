// 面试入场绑定 API：查会话的候选人/资料绑定关系。
// 绑定本身在聊天首轮由后端创建（聊天请求带 candidateName + materialIds），这里只读。

import { getJson } from './request'
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
