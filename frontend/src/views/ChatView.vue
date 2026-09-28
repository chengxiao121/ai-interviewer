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

// 开场示例问题，点击即开始
const starters = [
  { tag: 'JAVA', text: '来一道 Java 并发题' },
  { tag: 'REDIS', text: '追问一下 Redis 缓存三大问题' },
  { tag: 'HR', text: '开始一轮 HR 面模拟' },
]
</script>

<template>
  <div class="chat-view">
    <header class="page-head">
      <div class="title-block">
        <p class="eyebrow">LIVE · 面试进行中</p>
        <h2>面试对话</h2>
      </div>
      <span class="session-tag" :title="chatStore.sessionId">NO. {{ chatStore.sessionId }}</span>
    </header>
    <div class="page-rule"></div>

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
        <div class="seal">面</div>
        <h3>模拟面试，现在开始</h3>
        <p class="sub">面试官已就位。说出你的方向，或从下面的推荐问题开始。</p>
        <div class="starters">
          <button
            v-for="s in starters"
            :key="s.tag"
            class="starter"
            @click="onSend(s.text)"
          >
            <span class="tag">{{ s.tag }}</span>
            <span class="text">{{ s.text }}</span>
          </button>
        </div>
        <p class="hint">Enter 发送 · Shift + Enter 换行 · 可随时「停止」打断面试官</p>
      </div>
    </div>

    <div v-if="chatStore.error" class="error-bar">
      <span class="mark">※</span>{{ chatStore.error }}
    </div>

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
  position: relative;
  z-index: 1;
}
.session-tag {
  font-family: var(--mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--ink-soft);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-bottom: 3px;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 28px 48px 28px;
  display: flex;
  flex-direction: column;
  gap: 26px;
}

/* 欢迎页：面试开场 */
.welcome {
  margin: auto;
  max-width: 560px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.seal {
  width: 72px;
  height: 72px;
  background: var(--cinnabar);
  color: #fdf4e7;
  font-family: var(--serif);
  font-size: 44px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  box-shadow: inset 0 0 0 2px rgba(253, 244, 231, 0.35), 0 6px 18px rgba(143, 44, 32, 0.22);
  margin-bottom: 26px;
  animation: rise 0.5s ease both;
}
.welcome h3 {
  margin: 0;
  font-family: var(--serif);
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--ink-strong);
  animation: rise 0.5s ease 0.08s both;
}
.welcome .sub {
  margin: 12px 0 30px;
  color: var(--ink-soft);
  font-size: 14px;
  letter-spacing: 0.04em;
  animation: rise 0.5s ease 0.16s both;
}
.starters {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
  animation: rise 0.5s ease 0.24s both;
}
.starter {
  appearance: none;
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 13px 18px;
  background: var(--paper-raise);
  border: 1px solid var(--rule-strong);
  border-radius: 4px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}
.starter:hover {
  border-color: var(--cinnabar);
  transform: translateX(4px);
  box-shadow: -3px 0 0 var(--cinnabar);
}
.starter .tag {
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.18em;
  color: var(--cinnabar);
  border: 1px solid rgba(176, 58, 43, 0.4);
  padding: 3px 7px;
  border-radius: 3px;
  flex-shrink: 0;
}
.starter .text {
  font-family: var(--kai);
  font-size: 15.5px;
  color: var(--ink);
  letter-spacing: 0.03em;
}
.hint {
  margin: 26px 0 0;
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--ink-soft);
  animation: rise 0.5s ease 0.32s both;
}

/* 错误批注条 */
.error-bar {
  margin: 0 32px;
  padding: 10px 16px;
  background: var(--cinnabar-wash);
  border-top: 1px solid rgba(176, 58, 43, 0.35);
  color: var(--cinnabar-deep);
  font-size: 13px;
}
.error-bar .mark {
  margin-right: 10px;
  font-weight: 700;
}
</style>
