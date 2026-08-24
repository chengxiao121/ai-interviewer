<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionsStore } from '@/stores/sessions'
import { useChatStore } from '@/stores/chat'
import MessageBubble from '@/components/MessageBubble.vue'

const sessionsStore = useSessionsStore()
const chatStore = useChatStore()
const router = useRouter()

onMounted(() => {
  sessionsStore.loadList()
})

async function viewHistory(id: string) {
  await sessionsStore.loadHistory(id)
}

async function switchAndChat(id: string) {
  await sessionsStore.switchTo(id)
  router.push('/chat')
}

async function clearSession(id: string) {
  if (!confirm(`确认清空会话「${id}」的记忆？此操作不可恢复。`)) return
  await sessionsStore.clear(id)
  // 若清空的是当前聊天会话，重置聊天页
  if (chatStore.sessionId === id) {
    chatStore.newSession()
  }
}
</script>

<template>
  <div class="sessions-view">
    <header class="page-header">
      <h2>会话管理</h2>
      <button class="btn" @click="sessionsStore.loadList()" :disabled="sessionsStore.loading">
        刷新列表
      </button>
    </header>

    <div class="sessions-body">
      <section class="session-list">
        <div v-if="sessionsStore.loading && sessionsStore.sessions.length === 0" class="loading">
          加载中…
        </div>
        <div v-else-if="sessionsStore.sessions.length === 0" class="empty">
          暂无会话，去聊天页开始第一轮对话吧。
        </div>
        <ul v-else>
          <li
            v-for="id in sessionsStore.sessions"
            :key="id"
            class="session-item"
            :class="{ active: sessionsStore.current === id }"
          >
            <div class="sid" :title="id">{{ id }}</div>
            <div class="ops">
              <button @click="viewHistory(id)">查看历史</button>
              <button class="primary" @click="switchAndChat(id)">载入聊天</button>
              <button class="danger" @click="clearSession(id)">清空</button>
            </div>
          </li>
        </ul>
      </section>

      <section class="history-panel">
        <h3>历史消息</h3>
        <div v-if="sessionsStore.loading" class="loading">加载中…</div>
        <div v-else-if="sessionsStore.history.length === 0" class="empty">
          选择左侧会话查看历史消息
        </div>
        <div v-else class="history-list">
          <MessageBubble
            v-for="(msg, i) in sessionsStore.history"
            :key="i"
            :message="{ role: msg.role?.toUpperCase() === 'USER' ? 'user' : 'assistant', content: msg.content }"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.sessions-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  flex: 1;
  min-width: 0;
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border);
}
.page-header h2 { margin: 0; font-size: 16px; }
.sessions-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.session-list {
  flex: 0 0 320px;
  border-right: 1px solid var(--color-border);
  overflow-y: auto;
  padding: 12px;
}
.session-item {
  padding: 12px;
  border-radius: 8px;
  border: 1px solid var(--color-border);
  margin-bottom: 10px;
  background: var(--color-bg);
}
.session-item.active {
  border-color: var(--color-primary);
}
.sid {
  font-size: 13px;
  word-break: break-all;
  margin-bottom: 8px;
  color: var(--color-text);
}
.ops {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.ops button {
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid var(--color-border);
  background: var(--color-bg);
  cursor: pointer;
  font-size: 12px;
}
.ops button.primary {
  background: var(--color-primary);
  color: #fff;
  border-color: var(--color-primary);
}
.ops button.danger {
  color: var(--color-danger);
  border-color: var(--color-danger);
}
.history-panel {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}
.history-panel h3 {
  margin: 0 0 16px;
  font-size: 15px;
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.loading, .empty {
  color: var(--color-text-muted);
  padding: 24px;
  text-align: center;
}
</style>
