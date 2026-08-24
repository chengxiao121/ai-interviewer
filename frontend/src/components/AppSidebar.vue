<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'

const route = useRoute()
const chatStore = useChatStore()

const nav = [
  { name: 'chat', label: '面试对话', path: '/chat', icon: '💬' },
  { name: 'sessions', label: '会话管理', path: '/sessions', icon: '🗂' },
  { name: 'knowledge', label: '知识库', path: '/knowledge', icon: '📚' },
]
</script>

<template>
  <aside class="sidebar">
    <div class="logo">
      <span class="logo-icon">🎯</span>
      <span class="logo-text">AI 面试官</span>
    </div>

    <nav class="nav">
      <RouterLink
        v-for="item in nav"
        :key="item.name"
        :to="item.path"
        class="nav-item"
        :class="{ active: route.name === item.name }"
      >
        <span class="icon">{{ item.icon }}</span>
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="session-info">
      <div class="label">当前会话</div>
      <div class="session-id" :title="chatStore.sessionId">{{ chatStore.sessionId }}</div>
      <button class="btn-new" @click="chatStore.newSession()">＋ 新建会话</button>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 220px;
  flex: 0 0 220px;
  height: 100vh;
  background: var(--color-bg-sidebar);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
  box-sizing: border-box;
}
.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px 20px;
  font-weight: 700;
  font-size: 16px;
}
.logo-icon { font-size: 20px; }
.nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  text-decoration: none;
  color: var(--color-text-muted);
  font-size: 14px;
  transition: background 0.15s, color 0.15s;
}
.nav-item:hover {
  background: var(--color-bg-soft);
  color: var(--color-text);
}
.nav-item.active {
  background: var(--color-primary);
  color: #fff;
}
.icon { font-size: 16px; }
.session-info {
  margin-top: auto;
  padding: 12px 10px;
  border-top: 1px solid var(--color-border);
}
.session-info .label {
  font-size: 11px;
  color: var(--color-text-muted);
  margin-bottom: 4px;
}
.session-id {
  font-size: 12px;
  color: var(--color-text);
  word-break: break-all;
  max-height: 40px;
  overflow: hidden;
  margin-bottom: 8px;
}
.btn-new {
  width: 100%;
  padding: 8px;
  border: 1px dashed var(--color-border);
  background: transparent;
  border-radius: 8px;
  cursor: pointer;
  color: var(--color-text);
  font-size: 13px;
}
.btn-new:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}
</style>
