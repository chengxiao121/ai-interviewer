<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import MessageBubble from '@/components/MessageBubble.vue'
import ChatInput from '@/components/ChatInput.vue'

const chatStore = useChatStore()
const scrollRef = ref<HTMLDivElement | null>(null)

// 是否流式生成中的最后一条 assistant 消息
const streamingLast = computed(() => chatStore.streaming)

// 消息变化时自动滚到底
watch(
  () => chatStore.messages.map((m) => m.content).join(''),
  async () => {
    await nextTick()
    if (scrollRef.value) {
      scrollRef.value.scrollTop = scrollRef.value.scrollHeight
    }
  },
)

function onSend(text: string) {
  chatStore.send(text)
}
function onAbort() {
  chatStore.abort()
}
</script>

<template>
  <div class="chat-view">
    <header class="chat-header">
      <h2>面试对话</h2>
      <span class="session-tag">会话：{{ chatStore.sessionId }}</span>
    </header>

    <div ref="scrollRef" class="messages">
      <template v-if="chatStore.messages.length > 0">
        <MessageBubble
          v-for="(msg, i) in chatStore.messages"
          :key="i"
          :message="msg"
          :streaming="streamingLast && i === chatStore.messages.length - 1 && msg.role === 'assistant'"
        />
      </template>
      <div v-else class="welcome">
        <div class="welcome-icon">🎯</div>
        <h3>AI 智能面试官</h3>
        <p>用自己做的 AI 面试官准备面试。</p>
        <p class="hint">试试说：「考我一道 Java 并发题」</p>
      </div>
    </div>

    <div v-if="chatStore.error" class="error-bar">{{ chatStore.error }}</div>

    <ChatInput
      :disabled="!chatStore.canSend"
      :streaming="chatStore.streaming"
      @send="onSend"
      @abort="onAbort"
    />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  flex: 1;
  min-width: 0;
}
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border);
}
.chat-header h2 {
  margin: 0;
  font-size: 16px;
}
.session-tag {
  font-size: 12px;
  color: var(--color-text-muted);
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.welcome {
  margin: auto;
  text-align: center;
  color: var(--color-text-muted);
}
.welcome-icon { font-size: 48px; margin-bottom: 12px; }
.welcome h3 { margin: 0 0 8px; color: var(--color-text); }
.welcome .hint {
  margin-top: 16px;
  padding: 8px 14px;
  background: var(--color-bg-soft);
  border-radius: 8px;
  display: inline-block;
  color: var(--color-primary);
}
.error-bar {
  padding: 8px 16px;
  background: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
  font-size: 13px;
}
</style>
