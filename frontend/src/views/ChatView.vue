<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import MessageBubble from '@/components/MessageBubble.vue'
import ChatInput from '@/components/ChatInput.vue'
import EntryForm from '@/components/EntryForm.vue'
import type { InterviewEntry } from '@/stores/chat'

const chatStore = useChatStore()
const router = useRouter()

/** 跳转本场评估报告（候选人名即 candidateId——绑定恢复时后端 candidateId 就写入 candidateName） */
function viewReport() {
  if (!chatStore.sessionId) return
  const qs = chatStore.candidateName ? `?candidateId=${encodeURIComponent(chatStore.candidateName)}` : ''
  router.push(`/report/${chatStore.sessionId}${qs}`)
}

/**
 * 结束面试（阶段 11）：确认 → 调 finish 接口（状态机置 FINISHED）→ 跳评估报告页。
 * 结束后面试官不再出题（后端每轮注入已结束提示）；报告门禁以此状态解锁。
 * 收尾总结由报告的"总体结论"承担，不再额外走一轮 LLM 收尾（避免特殊消息通道）。
 */
async function finishInterview() {
  if (!chatStore.sessionId || chatStore.sessionStatus === 'FINISHED') return
  if (!window.confirm('确定结束本场面试吗？结束后面试官不再出题，评估报告将解锁（不可恢复）。')) {
    return
  }
  finishing.value = true
  try {
    await chatStore.finishInterview()
    viewReport()
  } catch (e) {
    chatStore.error = e instanceof Error ? e.message : '结束面试失败，请重试'
  } finally {
    finishing.value = false
  }
}
const finishing = ref(false)
/** 已结束的会话：头部眉标与按钮随之切换 */
const isFinished = computed(() => chatStore.sessionStatus === 'FINISHED')
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
function onStart(entry: InterviewEntry) {
  chatStore.startInterview(entry)
}

// 开场示例问题，点击即开始（入场完成后可见）
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
        <p class="eyebrow">{{ isFinished ? 'FINISHED · 面试已结束' : 'LIVE · 面试进行中' }}</p>
        <h2>面试对话</h2>
      </div>
      <div class="head-tags">
        <span v-if="chatStore.candidateName" class="candidate-tag" title="候选人">
          {{ chatStore.candidateName }}
        </span>
        <span class="session-tag" :title="chatStore.sessionId">NO. {{ chatStore.sessionId }}</span>
        <!-- 报告入口（阶段 11 门禁）：仅已结束的会话可见 -->
        <button
          v-if="chatStore.sessionId && isFinished"
          class="report-entry"
          title="查看本场评估报告"
          @click="viewReport"
        >
          评估报告
        </button>
        <!-- 结束面试（阶段 11）：进行中的会话显示；确认后状态置 FINISHED 并跳报告 -->
        <button
          v-else-if="chatStore.sessionId && !chatStore.entryNeeded"
          class="report-entry finish-entry"
          :disabled="finishing"
          title="结束本场面试，解锁评估报告"
          @click="finishInterview"
        >
          {{ finishing ? '结束中…' : '结束面试' }}
        </button>
      </div>
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
      <EntryForm v-else-if="chatStore.entryNeeded" @start="onStart" />
      <div v-else class="welcome">
        <h3>入场完成，面试官已就位</h3>
        <p class="sub">候选人：{{ chatStore.candidateName }}。说出你的方向，或从下面的推荐问题开始。</p>
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
.head-tags {
  display: flex;
  align-items: center;
  gap: 10px;
}
.candidate-tag {
  font-family: var(--serif);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--cinnabar);
  border: 1.5px solid var(--cinnabar);
  border-radius: 4px;
  padding: 3px 10px;
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
.report-entry {
  appearance: none;
  font-family: var(--serif);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--mint-deep);
  background: var(--mint-wash);
  border: 1px solid var(--mint);
  border-radius: 999px;
  padding: 4px 14px;
  cursor: pointer;
  transition: background 0.16s ease, transform 0.16s ease;
}
/* 结束面试：朱砂描边与报告入口区分（动作更"重"，视觉上也该更醒目） */
.finish-entry {
  color: var(--cinnabar, #a2372a);
  background: transparent;
  border-color: var(--cinnabar, #a2372a);
}
.finish-entry:hover {
  background: rgba(162, 55, 42, 0.06);
}
.finish-entry:disabled {
  opacity: 0.5;
  cursor: wait;
}
.report-entry:hover {
  background: rgba(63, 157, 116, 0.18);
  transform: translateY(-1px);
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 28px 48px 28px;
  display: flex;
  flex-direction: column;
  gap: 26px;
}

/* 欢迎页：入场完成后的开场 */
.welcome {
  margin: auto;
  max-width: 560px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
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
  border: 1px solid var(--rule);
  border-radius: 12px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}
.starter:hover {
  border-color: var(--mint);
  transform: translateX(4px);
  box-shadow: 0 3px 12px rgba(63, 157, 116, 0.16);
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

/* 错误提示条 */
.error-bar {
  margin: 0 32px;
  padding: 10px 16px;
  background: var(--danger-wash);
  border-top: 1px solid rgba(209, 106, 95, 0.35);
  color: var(--danger-deep);
  font-size: 13px;
}
.error-bar .mark {
  margin-right: 10px;
  font-weight: 700;
}
</style>
