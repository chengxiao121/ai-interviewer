import { postSse } from '@/utils/sse'
import { sseUrl, getJson, deleteJson } from './request'
import type { HistoryMessageDto, ClearResult } from '@/types'

/**
 * 面试对话 SSE 流式。
 * @param userMessage 用户输入
 * @param sessionId 会话 id
 * @param onChunk 每收到一片文本回调
 * @param onDone 流结束
 * @param onError 出错
 * @param materialIds 可选：上传资料 id 列表（带则直通资料流水线，跳过意图路由）
 * @returns AbortController，可中断
 */
export function chatSse(
  userMessage: string,
  sessionId: string,
  onChunk: (text: string) => void,
  onDone?: () => void,
  onError?: (err: Error) => void,
  materialIds?: string[],
): AbortController {
  return postSse({
    url: sseUrl('/assistant/chat'),
    body: {
      userMessage,
      sessionId,
      ...(materialIds && materialIds.length > 0 ? { materialIds } : {}),
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
