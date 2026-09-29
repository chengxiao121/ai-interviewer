<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { listResumes, uploadMaterial, isAllowedMaterialFile } from '@/api/materials'
import type { ResumeMeta } from '@/types'
import type { InterviewEntry } from '@/stores/chat'

/**
 * 面试入场表单（阶段 8）：简历必传、JD 可选、候选人姓名必填。
 * 提交后 emit('start')，真正的绑定由首轮聊天消息在后端落库。
 */
const emit = defineEmits<{
  (e: 'start', entry: InterviewEntry): void
}>()

// ===== 候选人姓名 =====
const candidateName = ref('')

// ===== 简历（必选）：上传新简历为主路径，历史简历库默认收起 =====
const resumes = ref<ResumeMeta[]>([])
const resumesLoading = ref(true)
const loadError = ref('')
/** 简历库折叠状态（默认收起：避免"每次都是上一份简历"的错觉，也防误点历史项） */
const libraryOpen = ref(false)
/** 选中的简历：历史 id 或 刚上传的 id */
const selectedResume = ref<{ id: string; fileName: string } | null>(null)

const resumeInputRef = ref<HTMLInputElement | null>(null)
const resumeUploading = ref(false)
const resumeError = ref('')
const newResume = ref<{ id: string; fileName: string } | null>(null)
/** 去重提示：上传了与库中内容相同的简历时说明发生了什么 */
const dedupHint = ref('')

// ===== JD（可选）：仅上传 =====
const jdInputRef = ref<HTMLInputElement | null>(null)
const jdUploading = ref(false)
const jdError = ref('')
const jd = ref<{ id: string; fileName: string } | null>(null)

const canStart = computed(
  () => candidateName.value.trim().length > 0 && selectedResume.value !== null,
)

onMounted(loadResumes)

async function loadResumes() {
  resumesLoading.value = true
  loadError.value = ''
  try {
    resumes.value = await listResumes()
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : String(err)
  } finally {
    resumesLoading.value = false
  }
}

/** 选历史简历（再点一次取消） */
function pickResume(meta: ResumeMeta) {
  if (selectedResume.value?.id === meta.materialId) {
    selectedResume.value = null
  } else {
    selectedResume.value = { id: meta.materialId, fileName: meta.fileName }
  }
}

/** 上传新简历（成功后自动选中；与库中内容相同时提示已复用） */
async function onResumeChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  resumeError.value = ''
  dedupHint.value = ''
  if (!isAllowedMaterialFile(file)) {
    resumeError.value = `「${file.name}」不是 txt/md 文本文件`
    return
  }
  resumeUploading.value = true
  try {
    const result = await uploadMaterial(file, 'RESUME')
    newResume.value = { id: result.materialId, fileName: result.fileName }
    selectedResume.value = { id: result.materialId, fileName: result.fileName }
    if (result.duplicated) {
      dedupHint.value = `库中已有内容相同的简历，已复用该份材料（文件名记为「${result.fileName}」）`
    }
  } catch (err) {
    resumeError.value = err instanceof Error ? err.message : String(err)
  } finally {
    resumeUploading.value = false
  }
}

/** 上传 JD（可选，可移除重传） */
async function onJdChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  jdError.value = ''
  if (!isAllowedMaterialFile(file)) {
    jdError.value = `「${file.name}」不是 txt/md 文本文件`
    return
  }
  jdUploading.value = true
  try {
    const result = await uploadMaterial(file, 'JD')
    jd.value = { id: result.materialId, fileName: result.fileName }
  } catch (err) {
    jdError.value = err instanceof Error ? err.message : String(err)
  } finally {
    jdUploading.value = false
  }
}

function start() {
  if (!canStart.value || !selectedResume.value) return
  const materialIds = [selectedResume.value.id, ...(jd.value ? [jd.value.id] : [])]
  const materialNames = [selectedResume.value.fileName, ...(jd.value ? [jd.value.fileName] : [])]
  emit('start', {
    candidateName: candidateName.value.trim(),
    materialIds,
    materialNames,
  })
}
</script>

<template>
  <div class="entry">
    <div class="seal">面</div>
    <h3>开始一场正式面试</h3>
    <p class="sub">面试基于简历进行：候选人姓名 + 简历为必填，JD 可选。</p>

    <!-- ① 候选人姓名 -->
    <div class="field">
      <label class="field-label">候选人姓名 <span class="req">*</span></label>
      <input
        v-model="candidateName"
        class="name-input"
        type="text"
        placeholder="如：张三（用于跨会话识别同一候选人）"
        maxlength="64"
      />
    </div>

    <!-- ② 简历（必选）：上传为主路径，简历库默认收起 -->
    <div class="field">
      <label class="field-label">简历 <span class="req">*</span></label>

      <div class="upload-row">
        <button class="upload-btn" :disabled="resumeUploading" @click="resumeInputRef?.click()">
          {{ resumeUploading ? '上传中…' : newResume ? `✓ 已上传：${newResume.fileName}` : '＋ 上传简历（txt/md）' }}
        </button>
        <input ref="resumeInputRef" type="file" accept=".txt,.md,.markdown" hidden @change="onResumeChosen" />
        <span v-if="selectedResume" class="picked-name">
          将使用：{{ selectedResume.fileName }}
        </span>
      </div>
      <p v-if="dedupHint" class="hint-line">{{ dedupHint }}</p>
      <p v-if="resumeError" class="err">{{ resumeError }}</p>
      <p v-if="loadError" class="err">{{ loadError }}</p>

      <div v-if="resumesLoading" class="loading">读取历史简历中…</div>
      <template v-else-if="resumes.length > 0">
        <button
          class="library-toggle"
          type="button"
          :title="libraryOpen ? '收起简历库' : '展开简历库'"
          @click="libraryOpen = !libraryOpen"
        >
          <span class="rule-line"></span>
          <span class="library-label">或从简历库选择（{{ resumes.length }} 份）</span>
          <svg class="chev" :class="{ open: libraryOpen }" viewBox="0 0 12 12" width="10" height="10">
            <path d="M 2.5 4 l 3.5 4 l 3.5 -4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span class="rule-line"></span>
        </button>
        <div v-show="libraryOpen" class="resume-list">
          <button
            v-for="meta in resumes"
            :key="meta.materialId"
            class="resume-item"
            :class="{ picked: selectedResume?.id === meta.materialId }"
            @click="pickResume(meta)"
          >
            <span class="badge">简历</span>
            <span class="name">{{ meta.fileName }}</span>
            <span class="meta">{{ meta.charCount }} 字 · {{ meta.createdAt.slice(0, 10) }}</span>
          </button>
        </div>
      </template>
      <p v-else class="or">还没有历史简历，上传一份开始</p>
    </div>

    <!-- ③ JD（可选） -->
    <div class="field">
      <label class="field-label">岗位 JD <span class="opt">可选</span></label>
      <div class="upload-row">
        <button class="upload-btn" :disabled="jdUploading" @click="jdInputRef?.click()">
          {{ jdUploading ? '上传中…' : jd ? `✓ 已上传：${jd.fileName}` : '＋ 上传 JD（txt/md）' }}
        </button>
        <button v-if="jd && !jdUploading" class="clear-btn" @click="jd = null">移除</button>
        <input ref="jdInputRef" type="file" accept=".txt,.md,.markdown" hidden @change="onJdChosen" />
      </div>
      <p v-if="jdError" class="err">{{ jdError }}</p>
    </div>

    <button class="start-btn" :disabled="!canStart" @click="start">
      进入面试间 ↵
    </button>
    <p class="hint">同一姓名 + 同一份简历会被识别为同一候选人，薄弱点评测跨会话累积；换姓名即开启互不干扰的新身份。</p>
  </div>
</template>

<style scoped>
.entry {
  margin: auto;
  max-width: 560px;
  width: 100%;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.seal {
  width: 64px;
  height: 64px;
  margin: 0 auto 20px;
  background: var(--cinnabar);
  color: #fdf4e7;
  font-family: var(--serif);
  font-size: 38px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  box-shadow: inset 0 0 0 2px rgba(253, 244, 231, 0.35), 0 6px 18px rgba(143, 44, 32, 0.22);
  animation: rise 0.5s ease both;
}
.entry h3 {
  margin: 0;
  font-family: var(--serif);
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--ink-strong);
  animation: rise 0.5s ease 0.06s both;
}
.entry .sub {
  margin: 10px 0 24px;
  color: var(--ink-soft);
  font-size: 13.5px;
  letter-spacing: 0.03em;
  animation: rise 0.5s ease 0.12s both;
}

.field {
  margin-bottom: 18px;
  text-align: left;
  animation: rise 0.5s ease 0.18s both;
}
.field-label {
  display: block;
  font-family: var(--mono);
  font-size: 10.5px;
  letter-spacing: 0.18em;
  color: var(--ink-soft);
  margin-bottom: 8px;
}
.field-label .req {
  color: var(--cinnabar);
}
.field-label .opt {
  font-size: 9.5px;
  border: 1px solid var(--rule-strong);
  border-radius: 3px;
  padding: 1px 5px;
  margin-left: 4px;
  letter-spacing: 0.1em;
}

.name-input {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 14px;
  font-size: 14.5px;
  font-family: var(--sans);
  color: var(--ink-strong);
  background: var(--paper-raise);
  border: 1.5px solid var(--rule-strong);
  border-radius: 5px;
  outline: none;
  transition: border-color 0.18s ease;
}
.name-input:focus {
  border-color: var(--cinnabar);
}

.resume-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}

/* 简历库折叠开关（默认收起，上传是主路径） */
.library-toggle {
  appearance: none;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-top: 12px;
  padding: 2px 0;
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: var(--sans);
}
.library-toggle .rule-line {
  flex: 1;
  height: 1px;
  background: var(--rule);
}
.library-toggle .library-label {
  font-size: 12.5px;
  color: var(--ink-soft);
  letter-spacing: 0.08em;
}
.library-toggle:hover .library-label {
  color: var(--ink);
}
.library-toggle .chev {
  color: var(--ink-soft);
  transition: transform 0.18s ease;
  flex-shrink: 0;
}
.library-toggle .chev.open {
  transform: rotate(180deg);
}

/* 去重复用提示（信息级，非错误） */
.hint-line {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--ink-blue);
  letter-spacing: 0.03em;
}
.resume-item {
  appearance: none;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 14px;
  background: var(--paper-raise);
  border: 1px solid var(--rule-strong);
  border-radius: 4px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.resume-item:hover {
  border-color: var(--ink-blue);
}
.resume-item.picked {
  border-color: var(--cinnabar);
  box-shadow: -3px 0 0 var(--cinnabar);
}
.resume-item .badge {
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--ink-blue);
  border: 1px solid var(--ink-blue);
  border-radius: 3px;
  padding: 2px 6px;
  flex-shrink: 0;
}
.resume-item.picked .badge {
  color: var(--cinnabar);
  border-color: var(--cinnabar);
}
.resume-item .name {
  font-family: var(--kai);
  font-size: 14.5px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.resume-item .meta {
  margin-left: auto;
  font-family: var(--mono);
  font-size: 10px;
  color: var(--ink-soft);
  flex-shrink: 0;
}

.loading,
.or {
  font-size: 12.5px;
  color: var(--ink-soft);
  letter-spacing: 0.08em;
  margin: 6px 0;
}
.or {
  text-align: center;
}

.upload-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.upload-btn {
  appearance: none;
  padding: 8px 14px;
  font-size: 13px;
  letter-spacing: 0.05em;
  color: var(--ink);
  background: var(--paper-raise);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 4px;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;
}
.upload-btn:hover:not(:disabled) {
  border-color: var(--cinnabar);
  color: var(--cinnabar);
}
.upload-btn:disabled {
  opacity: 0.55;
  cursor: wait;
}
.picked-name {
  font-size: 12px;
  color: var(--ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.clear-btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--ink-soft);
  font-size: 12.5px;
  cursor: pointer;
}
.clear-btn:hover {
  color: var(--cinnabar);
}

.err {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--cinnabar-deep);
}

.start-btn {
  margin-top: 10px;
  appearance: none;
  padding: 12px 0;
  font-family: var(--serif);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.24em;
  color: #fdf4e7;
  background: var(--cinnabar);
  border: none;
  border-radius: 5px;
  cursor: pointer;
  transition: opacity 0.15s ease, transform 0.15s ease;
  animation: rise 0.5s ease 0.24s both;
}
.start-btn:hover:not(:disabled) {
  opacity: 0.92;
}
.start-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.hint {
  margin: 14px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-soft);
  letter-spacing: 0.03em;
  animation: rise 0.5s ease 0.3s both;
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
