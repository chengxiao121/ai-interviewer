import { postSse } from '@/utils/sse'
import { sseUrl, getJson, deleteJson } from './request'
import type { HistoryMessageDto, ClearResult, InterviewMode } from '@/types'

/**
 * 面试对话 SSE 流式（阶段 8：入场绑定；阶段 12：双模式）。
 * @param userMessage 用户输入
 * @param sessionId 会话 id
 * @param onChunk 每收到一片文本回调
 * @param onDone 流结束
 * @param onError 出错
 * @param entry 可选：入场信息（首轮携带；已入场的会话省略，由服务端绑定决定）
 *   - mode：面试模式（缺省 MATERIAL，旧行为不变）
 *   - 知识库模式：kbId（必填）+ questionCount（可选，缺省 20）
 *   - 资料模式：materialIds（必填，须含简历）
 * @returns AbortController，可中断
 */
export function chatSse(
  userMessage: string,
  sessionId: string,
  onChunk: (text: string) => void,
  onDone?: () => void,
  onError?: (err: Error) => void,
  entry?: {
    candidateName?: string
    materialIds?: string[]
    mode?: InterviewMode
    kbId?: number
    questionCount?: number
  },
): AbortController {
  return postSse({
    url: sseUrl('/assistant/chat'),
    body: {
      userMessage,
      sessionId,
      ...(entry?.candidateName ? { candidateName: entry.candidateName } : {}),
      ...(entry?.materialIds && entry.materialIds.length > 0 ? { materialIds: entry.materialIds } : {}),
      ...(entry?.mode ? { mode: entry.mode } : {}),
      ...(entry?.kbId ? { kbId: entry.kbId } : {}),
      ...(entry?.questionCount ? { questionCount: entry.questionCount } : {}),
    },
    onChunk,
    onDone,
    onError,
  })
}

/** 列出所有会话 id */
export function listSessions(): Promise<string[]> {
  return getJson<string[]>('/assistant/memory')
}

/** 查会话历史（滑动窗口内消息） */
export function getHistory(sessionId: string): Promise<HistoryMessageDto[]> {
  return getJson<HistoryMessageDto[]>(`/assistant/memory/${encodeURIComponent(sessionId)}`)
}

/** 清空会话 */
export function clearSession(sessionId: string): Promise<ClearResult> {
  return deleteJson<ClearResult>(`/assistant/memory/${encodeURIComponent(sessionId)}`)
}
