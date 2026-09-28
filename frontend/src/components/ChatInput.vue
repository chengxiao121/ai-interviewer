<script setup lang="ts">
import { ref, nextTick } from 'vue'
import {
  uploadMaterial,
  detectMaterialType,
  isAllowedMaterialFile,
  type MaterialType,
} from '@/api/materials'
import type { MaterialUploadResult } from '@/types'

const props = defineProps<{
  disabled?: boolean
  streaming?: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string, materialIds?: string[], materialNames?: string[]): void
  (e: 'abort'): void
}>()

const text = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

/** 已上传的资料（随消息一起发送，发送后清空） */
interface Attachment {
  materialId: string
  type: MaterialType
  fileName: string
  charCount: number
  file: File
  busy: boolean
}
const attachments = ref<Attachment[]>([])
const uploadError = ref('')
const uploading = ref(false)

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

function submit() {
  const content = text.value.trim()
  const hasMaterials = attachments.value.length > 0
  if ((!content && !hasMaterials) || props.disabled || uploading.value) return

  const ids = hasMaterials ? attachments.value.map((a) => a.materialId) : undefined
  const names = hasMaterials ? attachments.value.map((a) => a.fileName) : undefined
  emit('send', content || '请根据我上传的资料开始面试我。', ids, names)
  text.value = ''
  attachments.value = []
  uploadError.value = ''
  nextTick(autoResize)
}

function onKeydown(e: KeyboardEvent) {
  // Enter 发送，Shift+Enter 换行
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

/** 点 📎 选文件（可多选，逐个上传） */
function pickFiles() {
  uploadError.value = ''
  fileInputRef.value?.click()
}

async function onFilesChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return

  uploadError.value = ''
  uploading.value = true
  try {
    for (const file of files) {
      if (!isAllowedMaterialFile(file)) {
        uploadError.value = `「${file.name}」不是 txt/md 文本文件，已跳过`
        continue
      }
      const result = await uploadOne(file, detectMaterialType(file.name))
      attachments.value.push(result)
    }
  } catch (err) {
    uploadError.value = err instanceof Error ? err.message : String(err)
  } finally {
    uploading.value = false
  }
}

async function uploadOne(file: File, type: MaterialType): Promise<Attachment> {
  const result: MaterialUploadResult = await uploadMaterial(file, type)
  return {
    materialId: result.materialId,
    type: result.type,
    fileName: result.fileName,
    charCount: result.charCount,
    file,
    busy: false,
  }
}

/** 类型识别错了？点标签切换 = 用另一类型重新上传（服务端按类型+内容哈希去重，安全幂等） */
async function toggleType(att: Attachment) {
  if (att.busy || uploading.value) return
  const next: MaterialType = att.type === 'JD' ? 'RESUME' : 'JD'
  att.busy = true
  uploadError.value = ''
  try {
    const result = await uploadMaterial(att.file, next)
    Object.assign(att, {
      materialId: result.materialId,
      type: result.type,
      charCount: result.charCount,
    })
  } catch (err) {
    uploadError.value = err instanceof Error ? err.message : String(err)
  } finally {
    att.busy = false
  }
}

function removeAttachment(id: string) {
  attachments.value = attachments.value.filter((a) => a.materialId !== id)
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

      <!-- 已上传资料标签条 -->
      <div v-if="attachments.length > 0" class="attach-row">
        <span v-for="att in attachments" :key="att.materialId" class="attach-chip" :title="`${att.fileName} · ${att.charCount} 字符`">
          <button class="type-badge" :class="att.type.toLowerCase()" :disabled="att.busy" @click="toggleType(att)">
            {{ att.type === 'JD' ? 'JD' : '简历' }}
          </button>
          <span class="chip-name">{{ att.fileName }}</span>
          <button class="chip-remove" @click="removeAttachment(att.materialId)">×</button>
        </span>
        <span v-if="uploading" class="attach-loading">上传中…</span>
      </div>
      <p v-if="uploadError" class="upload-error">{{ uploadError }}</p>

      <textarea
        ref="textareaRef"
        v-model="text"
        :disabled="disabled && !streaming"
        placeholder="输入你的回答，或向面试官提问…… 也可以点 📎 附上 JD / 简历"
        rows="1"
        @input="autoResize"
        @keydown="onKeydown"
      ></textarea>
      <div class="input-foot">
        <button
          class="attach-btn"
          title="上传 JD / 简历（txt/md）"
          :disabled="disabled || uploading"
          @click="pickFiles"
        >
          📎 附件
        </button>
        <input
          ref="fileInputRef"
          type="file"
          accept=".txt,.md,.markdown"
          multiple
          hidden
          @change="onFilesChosen"
        />
        <button v-if="!streaming" class="btn ink send" :disabled="disabled || uploading" @click="submit">
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

/* 资料标签条 */
.attach-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.attach-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 4px 8px;
  background: var(--paper);
  border: 1px solid var(--rule);
  border-radius: 5px;
  max-width: 320px;
}
.type-badge {
  appearance: none;
  border: 1.5px solid var(--ink-blue);
  color: var(--ink-blue);
  background: transparent;
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  padding: 1px 6px;
  border-radius: 3px;
  cursor: pointer;
  flex-shrink: 0;
  transition: opacity 0.15s ease;
}
.type-badge:hover:not(:disabled) {
  opacity: 0.75;
}
.type-badge:disabled {
  opacity: 0.45;
  cursor: wait;
}
.chip-name {
  font-size: 12px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chip-remove {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--ink-soft);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  padding: 0 2px;
}
.chip-remove:hover {
  color: var(--cinnabar);
}
.attach-loading {
  font-size: 12px;
  color: var(--ink-soft);
  letter-spacing: 0.06em;
}
.upload-error {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--cinnabar-deep);
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
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px dashed var(--rule);
}
.attach-btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--ink-soft);
  font-size: 12.5px;
  letter-spacing: 0.06em;
  cursor: pointer;
  padding: 6px 8px;
  border-radius: 4px;
  transition: color 0.15s ease, background 0.15s ease;
}
.attach-btn:hover:not(:disabled) {
  color: var(--ink);
  background: rgba(42, 36, 29, 0.05);
}
.attach-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.send,
.stop {
  font-size: 13px;
  letter-spacing: 0.14em;
  padding: 9px 18px;
}
</style>
