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
  <div class="bubble" :class="message.role">
    <div class="avatar">{{ message.role === 'user' ? '我' : 'AI' }}</div>
    <div class="content">
      <div class="role-label">{{ message.role === 'user' ? '求职者' : '面试官' }}</div>
      <div class="text">
        <template v-if="message.content">
          <div class="markdown-body" v-html="renderedHtml"></div>
        </template>
        <template v-else>
          {{ streaming ? '正在思考…' : '' }}
        </template>
        <span v-if="streaming && message.content" class="cursor">▌</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bubble {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.bubble.user {
  flex-direction: row-reverse;
}
.avatar {
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}
.bubble.user .avatar {
  background: var(--color-primary);
  color: #fff;
}
.bubble.assistant .avatar {
  background: var(--color-bg-soft);
  color: var(--color-text);
}
.content {
  max-width: 78%;
}
.bubble.user .content {
  text-align: right;
}
.role-label {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 4px;
}
.text {
  padding: 10px 14px;
  border-radius: 12px;
  word-break: break-word;
  line-height: 1.6;
  font-size: 14px;
}
.bubble.user .text {
  background: var(--color-primary);
  color: #fff;
}
.bubble.assistant .text {
  background: var(--color-bg-soft);
  color: var(--color-text);
}
.cursor {
  animation: blink 1s steps(2) infinite;
  color: var(--color-primary);
}
@keyframes blink {
  to { opacity: 0; }
}

/* Markdown 基础样式：让渲染后的标题/列表/引用/加粗有层次 */
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
  margin: 12px 0 8px;
  font-weight: 600;
  line-height: 1.4;
}
.markdown-body :deep(h3) {
  font-size: 15px;
}
.markdown-body :deep(p) {
  margin: 8px 0;
}
.markdown-body :deep(p:first-child) {
  margin-top: 0;
}
.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0;
  padding-left: 20px;
}
.markdown-body :deep(li) {
  margin: 4px 0;
}
.markdown-body :deep(blockquote) {
  margin: 8px 0;
  padding: 6px 12px;
  border-left: 3px solid var(--color-primary);
  background: rgba(0, 0, 0, 0.03);
  border-radius: 0 6px 6px 0;
  color: var(--color-text-muted);
}
.markdown-body :deep(code) {
  padding: 2px 5px;
  background: rgba(0, 0, 0, 0.06);
  border-radius: 4px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
}
.markdown-body :deep(pre) {
  margin: 8px 0;
  padding: 10px 12px;
  background: rgba(0, 0, 0, 0.08);
  border-radius: 8px;
  overflow-x: auto;
}
.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
}
.markdown-body :deep(strong) {
  font-weight: 600;
}
.markdown-body :deep(hr) {
  margin: 12px 0;
  border: none;
  border-top: 1px solid var(--color-border);
}
</style>
