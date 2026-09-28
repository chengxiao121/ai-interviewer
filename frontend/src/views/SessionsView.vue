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
    <header class="page-head">
      <div class="title-block">
        <p class="eyebrow">HISTORY · 历史会话</p>
        <h2>会话管理</h2>
      </div>
      <div class="actions">
        <button class="btn" @click="sessionsStore.loadList()" :disabled="sessionsStore.loading">
          刷新列表
        </button>
      </div>
    </header>
    <div class="page-rule"></div>

    <div class="sessions-body">
      <section class="session-list">
        <div v-if="sessionsStore.loading && sessionsStore.sessions.length === 0" class="loading">
          加载中…
        </div>
        <div v-else-if="sessionsStore.sessions.length === 0" class="empty">
          <div class="empty-seal">空</div>
          暂无会话，去「面试对话」开始第一轮吧。
        </div>
        <ul v-else class="session-cards">
          <li
            v-for="id in sessionsStore.sessions"
            :key="id"
            class="session-card"
            :class="{ active: sessionsStore.current === id }"
          >
            <div class="card-top">
              <span class="exam-no">会话</span>
              <span v-if="sessionsStore.current === id" class="current-mark">进行中</span>
            </div>
            <div v-if="sessionsStore.candidateBySession[id]" class="card-candidate">
              <span class="cand-badge">候选人</span>
              <span class="cand-name">{{ sessionsStore.candidateBySession[id] }}</span>
            </div>
            <div class="sid" :title="id">{{ id }}</div>
            <div class="ops">
              <button @click="viewHistory(id)">查看记录</button>
              <button class="primary" @click="switchAndChat(id)">继续对话</button>
              <button class="danger" @click="clearSession(id)">删除会话</button>
            </div>
          </li>
        </ul>
      </section>

      <section class="history-panel">
        <div class="panel-title">
          <span class="stamp-mini">录</span>
          <h3>聊天记录</h3>
        </div>
        <div v-if="sessionsStore.loading" class="loading">加载中…</div>
        <div v-else-if="sessionsStore.history.length === 0" class="empty">
          选择左侧会话，查看当时的问答记录
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
  position: relative;
  z-index: 1;
}
.sessions-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* 会话列表 */
.session-list {
  flex: 0 0 348px;
  border-right: 1px solid var(--rule-strong);
  overflow-y: auto;
  padding: 18px 16px;
  background: rgba(42, 36, 29, 0.025);
}
.session-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.session-card {
  padding: 13px 14px;
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper-raise);
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}
.session-card:hover {
  border-color: var(--rule-strong);
  transform: translateY(-1px);
}
.session-card.active {
  border-color: var(--cinnabar);
  box-shadow: -3px 0 0 var(--cinnabar);
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
}
.exam-no {
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.3em;
  color: var(--ink-soft);
}
.card-candidate {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 7px;
}
.card-candidate .cand-badge {
  font-family: var(--mono);
  font-size: 8.5px;
  letter-spacing: 0.14em;
  color: var(--cinnabar);
  border: 1px solid var(--cinnabar);
  border-radius: 3px;
  padding: 1px 5px;
  flex-shrink: 0;
}
.card-candidate .cand-name {
  font-family: var(--serif);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-top .current-mark {
  font-family: var(--serif);
  font-size: 10.5px;
  letter-spacing: 0.2em;
  color: var(--cinnabar);
  border: 1.5px solid var(--cinnabar);
  border-radius: 4px;
  padding: 2px 6px;
}
.sid {
  font-family: var(--mono);
  font-size: 12.5px;
  word-break: break-all;
  margin-bottom: 10px;
  color: var(--ink-strong);
  line-height: 1.55;
}
.ops {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.ops button {
  appearance: none;
  padding: 5px 11px;
  border-radius: 4px;
  border: 1px solid var(--rule-strong);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--ink);
  letter-spacing: 0.06em;
  transition: all 0.16s ease;
}
.ops button:hover {
  border-color: var(--ink);
  background: rgba(42, 36, 29, 0.05);
}
.ops button.primary {
  background: var(--ink);
  color: var(--paper-bright);
  border-color: var(--ink);
}
.ops button.primary:hover {
  background: var(--ink-strong);
}
.ops button.danger {
  color: var(--cinnabar);
  border-color: rgba(176, 58, 43, 0.45);
}
.ops button.danger:hover {
  border-color: var(--cinnabar);
  background: var(--cinnabar-wash);
}

/* 记录面板 */
.history-panel {
  flex: 1;
  overflow-y: auto;
  padding: 20px 32px 28px;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--rule);
}
.panel-title .stamp-mini {
  width: 26px;
  height: 26px;
  font-family: var(--serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--cinnabar);
  border: 1.5px solid var(--cinnabar);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.panel-title h3 {
  margin: 0;
  font-family: var(--serif);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--ink-strong);
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 26px;
  max-width: 860px;
}
.loading,
.empty {
  color: var(--ink-soft);
  padding: 40px 24px;
  text-align: center;
  font-size: 13.5px;
  letter-spacing: 0.06em;
}
.empty-seal {
  width: 44px;
  height: 44px;
  margin: 0 auto 14px;
  font-family: var(--serif);
  font-size: 22px;
  font-weight: 600;
  color: var(--ink-soft);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
