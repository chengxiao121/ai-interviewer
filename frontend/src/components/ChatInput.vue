<script setup lang="ts">
import { ref, nextTick } from 'vue'

const props = defineProps<{
  disabled?: boolean
  streaming?: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'abort'): void
}>()

const text = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

function submit() {
  const content = text.value.trim()
  if (!content || props.disabled) return
  emit('send', content)
  text.value = ''
  nextTick(autoResize)
}

function onKeydown(e: KeyboardEvent) {
  // Enter 发送，Shift+Enter 换行
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}
</script>

<template>
  <div class="chat-input">
    <textarea
      ref="textareaRef"
      v-model="text"
      :disabled="disabled && !streaming"
      placeholder="输入你的回答…（Enter 发送，Shift+Enter 换行）"
      rows="1"
      @input="autoResize"
      @keydown="onKeydown"
    />
    <button v-if="!streaming" class="btn send" :disabled="disabled" @click="submit">发送</button>
    <button v-else class="btn stop" @click="emit('abort')">停止</button>
  </div>
</template>

<style scoped>
.chat-input {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px;
  border-top: 1px solid var(--color-border);
  background: var(--color-bg);
}
textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 14px;
  line-height: 1.5;
  font-family: inherit;
  max-height: 160px;
  outline: none;
  background: var(--color-bg);
  color: var(--color-text);
}
textarea:focus {
  border-color: var(--color-primary);
}
.btn {
  flex: 0 0 auto;
  padding: 10px 18px;
  border-radius: 10px;
  border: none;
  font-size: 14px;
  cursor: pointer;
}
.btn.send {
  background: var(--color-primary);
  color: #fff;
}
.btn.send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn.stop {
  background: var(--color-danger);
  color: #fff;
}
</style>
