import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { chatSse } from '@/api/assistant'
import type { ChatMessage, HistoryMessageDto } from '@/types'

/**
 * 聊天 store：当前会话、消息列表、流式状态。
 * 唯一状态来源，view 只消费和派发 action。
 */
export const useChatStore = defineStore('chat', () => {
  /** 当前会话 id（默认 default） */
  const sessionId = ref<string>('default')
  /** 当前会话消息列表 */
  const messages = ref<ChatMessage[]>([])
  /** 是否正在生成 */
  const streaming = ref(false)
  /** 当前 SSE 控制器（用于中断） */
  let controller: AbortController | null = null
  /** 最近一次错误 */
  const error = ref<string>('')

  /** 是否可发送 */
  const canSend = computed(() => !streaming.value)

  /** 切换会话并载入历史（历史来自后端，role 为 USER/ASSISTANT） */
  function loadHistory(sessionIdVal: string, history: HistoryMessageDto[]) {
    sessionId.value = sessionIdVal
    messages.value = history.map(normalizeMessage)
    error.value = ''
  }

  /** 新建会话：生成 UUID，清空消息 */
  function newSession() {
    if (streaming.value) abort()
    sessionId.value = generateSessionId()
    messages.value = []
    error.value = ''
  }

  /** 发送消息：推入 user 消息后流式追加 assistant 消息 */
  function send(text: string) {
    const content = text.trim()
    if (!content || streaming.value) return

    // 推入用户消息
    messages.value.push({ role: 'user', content })
    // 占位 assistant 消息，流式追加
    const assistantMsg = ref<ChatMessage>({ role: 'assistant', content: '' })
    messages.value.push(assistantMsg.value)

    streaming.value = true
    error.value = ''

    controller = chatSse(
      content,
      sessionId.value,
      (chunk) => {
        // 流式追加到最后一条 assistant 消息
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant') {
          last.content += chunk
        }
      },
      () => {
        streaming.value = false
        controller = null
      },
      (err) => {
        streaming.value = false
        controller = null
        error.value = err.message
        // 失败时若 assistant 占位仍为空，移除占位
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant' && last.content === '') {
          messages.value.pop()
        }
      },
    )
  }

  /** 中断当前生成 */
  function abort() {
    if (controller) {
      controller.abort()
      controller = null
    }
    streaming.value = false
  }

  return {
    sessionId,
    messages,
    streaming,
    error,
    canSend,
    loadHistory,
    newSession,
    send,
    abort,
  }
})

/** 后端 role（USER/ASSISTANT）→ 前端 role（user/assistant） */
function normalizeMessage(m: HistoryMessageDto): ChatMessage {
  const role = m.role?.toUpperCase() === 'USER' ? 'user' : 'assistant'
  return { role, content: m.content }
}

/** 生成会话 id */
function generateSessionId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return 'sess-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
}
