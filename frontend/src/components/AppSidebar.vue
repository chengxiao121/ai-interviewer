<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'

const route = useRoute()
const chatStore = useChatStore()

const nav = [
  { name: 'chat', label: '面试对话', en: 'INTERVIEW', path: '/chat' },
  { name: 'sessions', label: '会话管理', en: 'SESSIONS', path: '/sessions' },
  { name: 'knowledge', label: '知识库', en: 'KNOWLEDGE', path: '/knowledge' },
]
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="seal">面</div>
      <div class="brand-text">
        <div class="name">AI 面试官</div>
        <div class="caption">MOCK INTERVIEW · STUDIO</div>
      </div>
    </div>

    <nav class="nav">
      <RouterLink
        v-for="(item, i) in nav"
        :key="item.name"
        :to="item.path"
        class="nav-item"
        :class="{ active: route.name === item.name }"
      >
        <span class="no">{{ String(i + 1).padStart(2, '0') }}</span>
        <span class="labels">
          <span class="label">{{ item.label }}</span>
          <span class="en">{{ item.en }}</span>
        </span>
      </RouterLink>
    </nav>

    <div class="session-info">
      <div class="label">当前会话 · SESSION</div>
      <div v-if="chatStore.candidateName" class="candidate-row">
        <span class="cand-badge">候选人</span>
        <span class="cand-name" :title="chatStore.candidateName">{{ chatStore.candidateName }}</span>
      </div>
      <div class="session-id" :title="chatStore.sessionId">{{ chatStore.sessionId }}</div>
      <button class="btn-new" @click="chatStore.newSession()">＋ 新面试</button>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 232px;
  flex: 0 0 232px;
  height: 100vh;
  background: var(--paper-raise);
  color: var(--ink);
  display: flex;
  flex-direction: column;
  padding: 24px 16px 18px;
  position: relative;
  z-index: 1;
  border-right: 1px solid var(--rule);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 6px 26px;
}
/* 品牌徽标：薄荷绿圆角方块 */
.seal {
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  background: var(--mint);
  color: #fff;
  font-family: var(--sans);
  font-size: 22px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  box-shadow: 0 3px 10px rgba(63, 157, 116, 0.28);
}
.brand-text .name {
  font-family: var(--sans);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--ink-strong);
}
.brand-text .caption {
  font-family: var(--mono);
  font-size: 8.5px;
  letter-spacing: 0.22em;
  color: var(--ink-soft);
  margin-top: 4px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border-radius: 10px;
  text-decoration: none;
  color: var(--ink-soft);
  transition: background 0.18s ease, color 0.18s ease;
}
.nav-item:hover {
  background: var(--paper-deep);
  color: var(--ink-strong);
}
.nav-item.active {
  background: var(--mint-wash);
  color: var(--mint-deep);
  font-weight: 600;
}
.no {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--mint);
  opacity: 0.85;
}
.nav-item.active .no {
  opacity: 1;
}
.labels {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.label {
  font-size: 13.5px;
  letter-spacing: 0.08em;
}
.en {
  font-family: var(--mono);
  font-size: 8px;
  letter-spacing: 0.2em;
  color: var(--ink-soft);
  opacity: 0.7;
}

.session-info {
  margin-top: auto;
  padding: 16px 10px 0;
  border-top: 1px solid var(--rule);
}
.session-info .label {
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.18em;
  color: var(--ink-soft);
  margin-bottom: 8px;
}
.candidate-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.cand-badge {
  font-family: var(--mono);
  font-size: 8.5px;
  letter-spacing: 0.16em;
  color: var(--mint-deep);
  border: 1px solid var(--mint);
  border-radius: 6px;
  padding: 2px 5px;
  flex-shrink: 0;
}
.cand-name {
  font-family: var(--sans);
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-id {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--ink-soft);
  word-break: break-all;
  max-height: 44px;
  overflow: hidden;
  line-height: 1.5;
  padding: 8px 10px;
  background: var(--paper);
  border: 1px solid var(--rule);
  border-radius: 10px;
  margin-bottom: 12px;
}
.btn-new {
  width: 100%;
  padding: 10px;
  background: var(--mint);
  color: #fff;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.1em;
  transition: background 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}
.btn-new:hover {
  background: var(--mint-deep);
  transform: translateY(-1px);
  box-shadow: 0 3px 10px rgba(63, 157, 116, 0.25);
}
.btn-new:active {
  transform: translateY(0);
}
</style>
