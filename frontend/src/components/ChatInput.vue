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
  <div class="chat-input-wrap">
    <div class="chat-input">
      <div class="input-head">
        <span class="dot"></span>
        <span class="label">回答区 · RESPONSE</span>
        <span class="key-hint">Enter 发送 · Shift+Enter 换行</span>
      </div>
      <textarea
        ref="textareaRef"
        v-model="text"
        :disabled="disabled && !streaming"
        placeholder="输入你的回答，或向面试官提问……"
        rows="1"
        @input="autoResize"
        @keydown="onKeydown"
      ></textarea>
      <div class="input-foot">
        <button v-if="!streaming" class="btn ink send" :disabled="disabled" @click="submit">
          发 送 ↵
        </button>
        <button v-else class="btn red stop" @click="emit('abort')">■ 结束回答</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-input-wrap {
  padding: 14px 32px 22px;
}
.chat-input {
  max-width: 720px;
  margin: 0 auto;
  background: var(--paper-raise);
  border: 1.5px solid var(--rule-strong);
  border-radius: 8px;
  padding: 12px 16px 12px;
  box-shadow: 0 4px 16px rgba(42, 36, 29, 0.08);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.chat-input:focus-within {
  border-color: var(--cinnabar);
  box-shadow: 0 4px 20px rgba(143, 44, 32, 0.14);
}

.input-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.input-head .dot {
  width: 7px;
  height: 7px;
  background: var(--cinnabar);
  border-radius: 1px;
  transform: rotate(45deg);
  flex-shrink: 0;
}
.input-head .label {
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.24em;
  color: var(--ink-soft);
}
.input-head .key-hint {
  margin-left: auto;
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.1em;
  color: rgba(147, 135, 111, 0.7);
}

textarea {
  display: block;
  width: 100%;
  resize: none;
  border: none;
  padding: 4px 0;
  font-size: 15px;
  line-height: 1.7;
  font-family: var(--sans);
  max-height: 160px;
  outline: none;
  background: transparent;
  color: var(--ink-strong);
}
textarea::placeholder {
  color: rgba(147, 135, 111, 0.65);
  font-family: var(--kai);
  letter-spacing: 0.06em;
}
textarea:disabled {
  opacity: 0.55;
}

.input-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px dashed var(--rule);
}
.send,
.stop {
  font-size: 13px;
  letter-spacing: 0.14em;
  padding: 9px 18px;
}
</style>
