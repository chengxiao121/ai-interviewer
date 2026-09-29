import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listSessions, getHistory, clearSession } from '@/api/assistant'
import { listInterviewSessions } from '@/api/interview'
import { useChatStore } from './chat'
import type { HistoryMessageDto, InterviewSessionDto } from '@/types'

/**
 * 会话管理 store：会话列表、绑定、历史、清空、切换载入。
 */
export const useSessionsStore = defineStore('sessions', () => {
  /** 所有会话 id（来自 Redis 聊天记忆 = 有对话记录的会话） */
  const sessions = ref<string[]>([])
  /** 全部入场绑定（含"绑了但没聊起来"的会话，按候选人分组展示用） */
  const bindings = ref<InterviewSessionDto[]>([])
  /** 当前选中的会话 id（用于高亮） */
  const current = ref<string>('')
  /** 加载中 */
  const loading = ref(false)
  /** 当前查看的历史 */
  const history = ref<HistoryMessageDto[]>([])
  /** sessionId → 候选人姓名（来自入场绑定，卡片展示用；未入场的会话无记录） */
  const candidateBySession = ref<Record<string, string>>({})

  /** 拉取会话列表（附带入场绑定列表与候选人映射） */
  async function loadList() {
    loading.value = true
    try {
      sessions.value = await listSessions()
      const map: Record<string, string> = {}
      let list: InterviewSessionDto[] = []
      try {
        list = await listInterviewSessions()
        for (const dto of list) {
          map[dto.sessionId] = dto.candidateId
        }
      } catch {
        /* 绑定列表拉取失败不影响会话列表展示 */
      }
      bindings.value = list
      candidateBySession.value = map
    } finally {
      loading.value = false
    }
  }

  /** 拉取某会话历史（仅展示，不切到聊天） */
  async function loadHistory(sessionId: string) {
    loading.value = true
    current.value = sessionId
    try {
      history.value = await getHistory(sessionId)
    } finally {
      loading.value = false
    }
  }

  /** 切换到某会话并载入历史到聊天页（同时恢复入场/候选人状态） */
  async function switchTo(sessionId: string) {
    const chat = useChatStore()
    current.value = sessionId
    loading.value = true
    try {
      const h = await getHistory(sessionId)
      chat.loadHistory(sessionId, h)
      await chat.applyBinding(sessionId)
    } finally {
      loading.value = false
    }
  }

  /** 清空会话 */
  async function clear(sessionId: string) {
    await clearSession(sessionId)
    // 从列表移除（绑定由后端级联删除）
    sessions.value = sessions.value.filter((s) => s !== sessionId)
    bindings.value = bindings.value.filter((b) => b.sessionId !== sessionId)
    delete candidateBySession.value[sessionId]
    if (current.value === sessionId) {
      current.value = ''
      history.value = []
    }
  }

  return {
    sessions,
    bindings,
    current,
    loading,
    history,
    candidateBySession,
    loadList,
    loadHistory,
    switchTo,
    clear,
  }
})
