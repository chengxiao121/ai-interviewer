<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import type { ChatMessage } from '@/types'

const props = defineProps<{
  message: ChatMessage
  /** 是否为流式生成中的最后一条 */
  streaming?: boolean
}>()

/**
 * 把 Markdown 文本转成 HTML 渲染。
 * marked 已经自带类型定义，不需要额外 @types/marked。
 */
const renderedHtml = computed(() => {
  return marked.parse(props.message.content || '', {
    async: false,
    gfm: true,
    breaks: true,   // 把单个 \n 也渲染成 <br>
  })
})
</script>

<template>
  <div class="entry" :class="message.role">
    <div class="stamp">{{ message.role === 'user' ? '答' : '问' }}</div>
    <div class="body">
      <div class="meta">
        <span class="role">{{ message.role === 'user' ? '求职者 · CANDIDATE' : '面试官 · EXAMINER' }}</span>
      </div>
      <div class="content">
        <template v-if="message.content">
          <div class="markdown-body" v-html="renderedHtml"></div>
        </template>
        <template v-else>
          <span class="thinking" v-if="streaming">正在思考</span>
        </template>
        <span v-if="streaming && message.content" class="cursor"></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.entry {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  animation: rise 0.4s ease both;
}

/* 问/答印章 */
.stamp {
  flex: 0 0 44px;
  width: 44px;
  height: 44px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--serif);
  font-size: 22px;
  font-weight: 600;
  margin-top: 2px;
}
.entry.assistant .stamp {
  color: var(--cinnabar);
  border: 2px solid var(--cinnabar);
  background: transparent;
  transform: rotate(-3deg);
  box-shadow: inset 0 0 0 1px rgba(176, 58, 43, 0.25);
}
.entry.user .stamp {
  background: var(--ink-blue);
  color: var(--paper-bright);
  transform: rotate(2deg);
  box-shadow: 0 1px 3px rgba(44, 61, 87, 0.3);
}

.body {
  min-width: 0;
}
.meta {
  margin-bottom: 6px;
}
.role {
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.22em;
}
.entry.assistant .role {
  color: var(--cinnabar);
}
.entry.user .role {
  color: rgba(44, 61, 87, 0.66);
}

/* 面试官：印在纸面上；求职者：粘贴的作答条 */
.content {
  position: relative;
  padding: 4px 2px;
  word-break: break-word;
  line-height: 1.75;
  font-size: 14.5px;
  max-width: 72ch;
}
.entry.assistant .content {
  border-left: 2px solid var(--rule);
  padding-left: 16px;
}
.entry.user .content {
  background: var(--paper-raise);
  border: 1px solid var(--rule);
  border-radius: 2px 10px 10px 10px;
  padding: 12px 16px;
  font-family: var(--kai);
  font-size: 16px;
  line-height: 1.85;
  color: var(--ink-blue);
  box-shadow: 2px 2px 0 rgba(42, 36, 29, 0.06);
}

/* 思考中 */
.thinking {
  color: var(--ink-soft);
  font-size: 13px;
  letter-spacing: 0.15em;
  animation: pulse 1.4s ease-in-out infinite;
}
.thinking::after {
  content: "…";
}
@keyframes pulse {
  0%, 100% { opacity: 0.45; }
  50% { opacity: 1; }
}

/* 流式光标：朱砂笔尖 */
.cursor {
  display: inline-block;
  width: 3px;
  height: 1em;
  margin-left: 3px;
  vertical-align: -0.15em;
  background: var(--cinnabar);
  animation: blink 1s steps(2) infinite;
}
@keyframes blink {
  to { opacity: 0; }
}

/* ---------- Markdown 笔录排版 ---------- */
.markdown-body :deep(*) {
  margin: 0;
  padding: 0;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  margin: 16px 0 8px;
  font-family: var(--serif);
  font-weight: 600;
  letter-spacing: 0.04em;
  line-height: 1.4;
  color: var(--ink-strong);
}
.markdown-body :deep(h3) {
  font-size: 15.5px;
}
.markdown-body :deep(h2),
.markdown-body :deep(h1) {
  font-size: 16.5px;
}
.markdown-body :deep(h1):first-child,
.markdown-body :deep(h2):first-child,
.markdown-body :deep(h3):first-child {
  margin-top: 0;
}
.markdown-body :deep(p) {
  margin: 9px 0;
}
.markdown-body :deep(p:first-child) {
  margin-top: 0;
}
.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 9px 0;
  padding-left: 22px;
}
.markdown-body :deep(li) {
  margin: 5px 0;
}
.markdown-body :deep(li::marker) {
  color: var(--cinnabar);
}
.markdown-body :deep(blockquote) {
  margin: 12px 0;
  padding: 10px 16px;
  border-left: 3px solid var(--cinnabar);
  background: var(--cinnabar-wash);
  border-radius: 0 6px 6px 0;
  color: var(--ink);
}
.markdown-body :deep(code) {
  padding: 2px 6px;
  background: var(--paper-deep);
  border-radius: 4px;
  font-family: var(--mono);
  font-size: 12.5px;
  color: var(--cinnabar-deep);
}
.markdown-body :deep(pre) {
  margin: 12px 0;
  padding: 14px 16px;
  background: #29241d;
  color: #ede4d3;
  border-radius: 8px;
  overflow-x: auto;
  box-shadow: inset 0 0 0 1px rgba(251, 246, 234, 0.08);
}
.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
  color: inherit;
  font-size: 12.5px;
  line-height: 1.7;
}
.markdown-body :deep(strong) {
  font-weight: 700;
  color: var(--ink-strong);
}
.markdown-body :deep(a) {
  color: var(--cinnabar);
  text-decoration-color: rgba(176, 58, 43, 0.4);
  text-underline-offset: 3px;
}
.markdown-body :deep(hr) {
  margin: 14px 0;
  border: none;
  border-top: 1px dashed var(--rule-strong);
}

/* 用户手写条内的代码恢复墨色，避免蓝底红字冲突 */
.entry.user .markdown-body :deep(code) {
  color: var(--ink-blue);
  background: rgba(44, 61, 87, 0.08);
}
.entry.user .markdown-body :deep(blockquote) {
  border-left-color: var(--ink-blue);
  background: rgba(44, 61, 87, 0.06);
}
</style>
