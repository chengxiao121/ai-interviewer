import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { chatSse } from '@/api/assistant'
import { getInterviewSession } from '@/api/interview'
import type { ChatMessage, HistoryMessageDto } from '@/types'

/** 入场信息：首轮发送时随消息带给后端（落绑定 interview_session） */
export interface InterviewEntry {
  candidateName: string
  materialIds: string[]
  materialNames: string[]
}

/**
 * 聊天 store：当前会话、消息列表、流式状态、入场状态。
 * 唯一状态来源，view 只消费和派发 action。
 */
export const useChatStore = defineStore('chat', () => {
/** 当前会话 id（初始即生成新 UUID：入场绑定制下 "default" 固定会话会永久占用候选人身份） */
const sessionId = ref<string>(generateSessionId())
  /** 当前会话消息列表 */
  const messages = ref<ChatMessage[]>([])
  /** 是否正在生成 */
  const streaming = ref(false)
  /** 当前 SSE 控制器（用于中断） */
  let controller: AbortController | null = null
  /** 最近一次错误 */
  const error = ref<string>('')

  // ===== 入场状态（阶段 8：简历必传，入场信息随首轮消息落库绑定）=====
  /** 当前会话是否还需要入场（新会话默认需要；已绑定/已有历史消息的会话不需要） */
  const entryNeeded = ref(true)
  /** 当前会话候选人姓名（展示用，入场时填写或从绑定恢复） */
  const candidateName = ref('')
  /** 待发送的入场信息（入场完成 → 首条消息携带；发送成功后清空） */
  let pendingSetup: InterviewEntry | null = null

  /** 是否可发送 */
  const canSend = computed(() => !streaming.value && !entryNeeded.value)

  /** 切换会话并载入历史（历史来自后端，role 为 USER/ASSISTANT） */
  function loadHistory(sessionIdVal: string, history: HistoryMessageDto[]) {
    sessionId.value = sessionIdVal
    messages.value = history.map(normalizeMessage)
    error.value = ''
    // 有历史消息的会话一定已入场（简历必传是服务端强制的）
    entryNeeded.value = false
  }

  /**
   * 查询会话绑定并恢复入场状态（刷新页面/切换会话后调用）：
   * 有绑定 → 恢复候选人展示、不需要入场；无绑定 → 需要重新入场。
   */
  async function applyBinding(sessionIdVal: string) {
    const dto = await getInterviewSession(sessionIdVal)
    if (dto) {
      candidateName.value = dto.candidateId
      entryNeeded.value = false
    } else {
      candidateName.value = ''
      entryNeeded.value = true
    }
  }

  /** 新建会话：生成 UUID，清空消息与入场状态 */
  function newSession() {
    if (streaming.value) abort()
    sessionId.value = generateSessionId()
    messages.value = []
    error.value = ''
    entryNeeded.value = true
    candidateName.value = ''
    pendingSetup = null
  }

  /**
   * 完成入场（EntryForm 提交）：暂存入场信息，聊天页切到"待开始"状态。
   * 真正的绑定在后端随首轮消息落库；本轮发送失败时入场信息保留，可原样重试。
   */
  function startInterview(setup: InterviewEntry) {
    pendingSetup = setup
    candidateName.value = setup.candidateName
    entryNeeded.value = false
    error.value = ''
  }

  /**
   * 发送消息：推入 user 消息后流式追加 assistant 消息。
   * 首条消息（有待发入场信息时）自动携带 candidateName + materialIds，发送成功后清空。
   * @param text 消息文本
   */
  function send(text: string) {
    const content = text.trim()
    if (!content || streaming.value || entryNeeded.value) return

    // 入场首轮：气泡显示 📎 资料前缀（仅本地展示；后端记忆里只存原话）
    const entry = pendingSetup
    const display = entry && entry.materialNames.length > 0
      ? `📎 ${entry.materialNames.join('、')}\n\n${content}`
      : content
    messages.value.push({ role: 'user', content: display })
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
        // 绑定已落库，入场信息使命完成
        pendingSetup = null
      },
      (err) => {
        streaming.value = false
        controller = null
        error.value = err.message
        // 失败时若 assistant 占位仍为空，移除占位；入场信息保留以便修正后重试
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant' && last.content === '') {
          messages.value.pop()
        }
      },
      entry ? { candidateName: entry.candidateName, materialIds: entry.materialIds } : undefined,
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
    entryNeeded,
    candidateName,
    loadHistory,
    applyBinding,
    newSession,
    startInterview,
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
