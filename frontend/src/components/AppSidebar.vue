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
  background: linear-gradient(180deg, #2b251d 0%, #241f18 100%);
  color: var(--paper-bright);
  display: flex;
  flex-direction: column;
  padding: 24px 16px 18px;
  position: relative;
  z-index: 1;
  border-right: 3px solid var(--ink-strong);
}
/* 书脊上的烫金细线 */
.sidebar::after {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  right: 6px;
  border-right: 1px solid rgba(251, 246, 234, 0.08);
  pointer-events: none;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 6px 26px;
}
/* 品牌徽标：干净的圆角方块 */
.seal {
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  background: var(--cinnabar);
  color: #fdf4e7;
  font-family: var(--serif);
  font-size: 24px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  box-shadow: inset 0 0 0 1.5px rgba(253, 244, 231, 0.35),
    0 2px 6px rgba(0, 0, 0, 0.25);
}
.brand-text .name {
  font-family: var(--serif);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.14em;
}
.brand-text .caption {
  font-family: var(--mono);
  font-size: 8.5px;
  letter-spacing: 0.22em;
  color: rgba(251, 246, 234, 0.5);
  margin-top: 4px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border-radius: 5px;
  text-decoration: none;
  color: rgba(251, 246, 234, 0.62);
  border-left: 2px solid transparent;
  transition: background 0.18s ease, color 0.18s ease, border-color 0.18s ease;
}
.nav-item:hover {
  background: rgba(251, 246, 234, 0.06);
  color: var(--paper-bright);
}
.nav-item.active {
  background: rgba(176, 58, 43, 0.18);
  border-left-color: var(--cinnabar);
  color: var(--paper-bright);
}
.no {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--cinnabar);
  opacity: 0.9;
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
  letter-spacing: 0.1em;
}
.en {
  font-family: var(--mono);
  font-size: 8px;
  letter-spacing: 0.2em;
  color: rgba(251, 246, 234, 0.34);
}

.session-info {
  margin-top: auto;
  padding: 16px 10px 0;
  border-top: 1px solid rgba(251, 246, 234, 0.14);
}
.session-info .label {
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.18em;
  color: rgba(251, 246, 234, 0.45);
  margin-bottom: 8px;
}
.session-id {
  font-family: var(--mono);
  font-size: 11px;
  color: rgba(251, 246, 234, 0.8);
  word-break: break-all;
  max-height: 44px;
  overflow: hidden;
  line-height: 1.5;
  padding: 8px 10px;
  border: 1px dashed rgba(251, 246, 234, 0.28);
  border-radius: 5px;
  margin-bottom: 12px;
}
.btn-new {
  width: 100%;
  padding: 10px;
  background: var(--paper-bright);
  color: var(--ink-strong);
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.12em;
  transition: background 0.18s ease, transform 0.18s ease;
}
.btn-new:hover {
  background: #fff;
  transform: translateY(-1px);
}
.btn-new:active {
  transform: translateY(0);
}
</style>
