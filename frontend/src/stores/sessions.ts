import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listSessions, getHistory, clearSession } from '@/api/assistant'
import { useChatStore } from './chat'
import type { HistoryMessageDto } from '@/types'

/**
 * 会话管理 store：会话列表、历史、清空、切换载入。
 */
export const useSessionsStore = defineStore('sessions', () => {
  /** 所有会话 id */
  const sessions = ref<string[]>([])
  /** 当前选中的会话 id（用于高亮） */
  const current = ref<string>('')
  /** 加载中 */
  const loading = ref(false)
  /** 当前查看的历史 */
  const history = ref<HistoryMessageDto[]>([])

  /** 拉取会话列表 */
  async function loadList() {
    loading.value = true
    try {
      sessions.value = await listSessions()
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

  /** 切换到某会话并载入历史到聊天页 */
  async function switchTo(sessionId: string) {
    const chat = useChatStore()
    current.value = sessionId
    loading.value = true
    try {
      const h = await getHistory(sessionId)
      chat.loadHistory(sessionId, h)
    } finally {
      loading.value = false
    }
  }

  /** 清空会话 */
  async function clear(sessionId: string) {
    await clearSession(sessionId)
    // 从列表移除
    sessions.value = sessions.value.filter((s) => s !== sessionId)
    if (current.value === sessionId) {
      current.value = ''
      history.value = []
    }
  }

  return {
    sessions,
    current,
    loading,
    history,
    loadList,
    loadHistory,
    switchTo,
    clear,
  }
})
