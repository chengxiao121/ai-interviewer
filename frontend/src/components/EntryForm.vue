<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { listResumes, uploadMaterial, isAllowedMaterialFile } from '@/api/materials'
import { listKnowledgeBases } from '@/api/knowledgeBase'
import type { ResumeMeta, InterviewMode, KnowledgeBaseDto } from '@/types'
import type { InterviewEntry } from '@/stores/chat'

/**
 * 面试入场表单（阶段 8：简历必传；阶段 12：第一步模式二选一）。
 * 资料面试 → 姓名 + 简历（必填）+ JD（可选）；知识库面试 → 姓名 + 题库（必选）+ 题数（默认 20）。
 * 提交后 emit('start')，真正的绑定/组卷由首轮聊天消息在后端落库。
 */
const emit = defineEmits<{
  (e: 'start', entry: InterviewEntry): void
}>()

// ===== 第一步：模式二选一（绑定后不可切换，缺省资料面试 = 旧行为）=====
const mode = ref<InterviewMode>('MATERIAL')

// ===== 候选人/练习者姓名（两种模式共用，跨会话薄弱点聚合的身份键）=====
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

// ===== 知识库面试（阶段 12）：题库必选 + 题数（默认 20）=====
const kbList = ref<KnowledgeBaseDto[]>([])
const kbLoading = ref(false)
const kbError = ref('')
/** 选中的知识库（必选；列表为空时引导去题库管理页上传） */
const selectedKb = ref<KnowledgeBaseDto | null>(null)
const questionCount = ref<number>(20)

/** 首次切到知识库模式时懒加载题库列表（避免资料面试用户白请求一次） */
async function loadKnowledgeBases() {
  if (kbList.value.length > 0 || kbLoading.value) return
  kbLoading.value = true
  kbError.value = ''
  try {
    kbList.value = await listKnowledgeBases()
  } catch (err) {
    kbError.value = err instanceof Error ? err.message : String(err)
  } finally {
    kbLoading.value = false
  }
}

watch(mode, (m) => {
  if (m === 'KNOWLEDGE') {
    loadKnowledgeBases()
  }
})

const canStart = computed(() => {
  if (candidateName.value.trim().length === 0) return false
  return mode.value === 'KNOWLEDGE' ? selectedKb.value !== null : selectedResume.value !== null
})

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
  if (!canStart.value) return
  const name = candidateName.value.trim()
  if (mode.value === 'KNOWLEDGE') {
    if (!selectedKb.value) return
    emit('start', {
      candidateName: name,
      mode: 'KNOWLEDGE',
      materialIds: [],
      materialNames: [],
      kbId: selectedKb.value.id,
      kbName: selectedKb.value.name,
      questionCount: questionCount.value,
    })
    return
  }
  if (!selectedResume.value) return
  const materialIds = [selectedResume.value.id, ...(jd.value ? [jd.value.id] : [])]
  const materialNames = [selectedResume.value.fileName, ...(jd.value ? [jd.value.fileName] : [])]
  emit('start', {
    candidateName: name,
    mode: 'MATERIAL',
    materialIds,
    materialNames,
  })
}
</script>

<template>
  <div class="entry">
    <div class="seal">面</div>
    <h3>开始一场面试</h3>
    <p class="sub">先选模式（入场后不可切换）：资料面试按简历深挖，知识库面试按题库背题自检。</p>

    <!-- ⓪ 模式二选一（阶段 12：第一步，绑定后不可变） -->
    <div class="mode-cards">
      <button
        class="mode-card"
        :class="{ picked: mode === 'MATERIAL' }"
        type="button"
        @click="mode = 'MATERIAL'"
      >
        <span class="mode-name">资料面试</span>
        <span class="mode-desc">基于简历 + JD 岗位画像，由面试官临场出题深挖</span>
      </button>
      <button
        class="mode-card"
        :class="{ picked: mode === 'KNOWLEDGE' }"
        type="button"
        @click="mode = 'KNOWLEDGE'"
      >
        <span class="mode-name">知识库面试</span>
        <span class="mode-desc">从选定题库抽题组卷，背题自检：这批题我能不能都答上来</span>
      </button>
    </div>

    <!-- ① 姓名（两种模式共用） -->
    <div class="field">
      <label class="field-label">{{ mode === 'KNOWLEDGE' ? '练习者姓名' : '候选人姓名' }} <span class="req">*</span></label>
      <input
        v-model="candidateName"
        class="name-input"
        type="text"
        placeholder="如：张三（用于跨会话识别同一人，薄弱点评测跨会话累积）"
        maxlength="64"
      />
    </div>

    <!-- ② 资料面试：简历（必选）+ JD（可选） -->
    <template v-if="mode === 'MATERIAL'">
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
    </template>

    <!-- ② 知识库面试：题库（必选）+ 题数（阶段 12） -->
    <template v-else>
      <div class="field">
        <label class="field-label">题库 <span class="req">*</span></label>
        <div v-if="kbLoading" class="loading">读取题库列表中…</div>
        <template v-else-if="kbList.length > 0">
          <div class="kb-list">
            <button
              v-for="kb in kbList"
              :key="kb.id"
              class="resume-item"
              :class="{ picked: selectedKb?.id === kb.id }"
              @click="selectedKb = selectedKb?.id === kb.id ? null : kb"
            >
              <span class="badge">题库</span>
              <span class="name">{{ kb.name }}</span>
              <span class="meta">{{ kb.questionCount }} 题</span>
            </button>
          </div>
        </template>
        <div v-else class="kb-empty">
          还没有知识库，先去
          <a class="kb-link" href="#/knowledge">题库管理页</a>
          上传一个「【考点】+【参考答案】」格式的文档
        </div>
        <p v-if="kbError" class="err">{{ kbError }}</p>
      </div>

      <div class="field">
        <label class="field-label">组卷题数 <span class="opt">默认 20</span></label>
        <div class="upload-row">
          <input
            v-model.number="questionCount"
            class="count-input"
            type="number"
            min="5"
            max="50"
            step="1"
          />
          <span class="picked-name">允许 5~50；题库题数不足时无法开卷（不会有多少出多少）</span>
        </div>
      </div>
    </template>

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
  box-shadow: 0 6px 18px rgba(63, 157, 116, 0.25);
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

/* 模式二选一卡片（阶段 12）：选中朱砂描边，未选中浅描边 */
.mode-cards {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  text-align: left;
  animation: rise 0.5s ease 0.14s both;
}
.mode-card {
  appearance: none;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  background: var(--paper-raise);
  border: 1.5px solid var(--rule-strong);
  border-radius: 10px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}
.mode-card:hover {
  border-color: var(--ink-blue);
}
.mode-card.picked {
  border-color: var(--cinnabar);
  box-shadow: 0 0 0 2px rgba(162, 55, 42, 0.12);
}
.mode-card .mode-name {
  font-family: var(--serif);
  font-size: 15.5px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: var(--ink-strong);
}
.mode-card.picked .mode-name {
  color: var(--cinnabar);
}
.mode-card .mode-desc {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-soft);
}

/* 知识库空状态引导 */
.kb-empty {
  margin-top: 4px;
  padding: 14px 16px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ink-soft);
  background: var(--paper-raise);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 6px;
  text-align: center;
}
.kb-link {
  color: var(--cinnabar);
  font-weight: 600;
}
/* 题库选择列表沿用 resume-item 结构，纵向列表 */
.kb-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 4px;
  max-height: 220px;
  overflow-y: auto;
}
/* 题数输入 */
.count-input {
  width: 96px;
  padding: 8px 12px;
  font-size: 14px;
  font-family: var(--sans);
  color: var(--ink-strong);
  background: var(--paper-raise);
  border: 1.5px solid var(--rule-strong);
  border-radius: 5px;
  outline: none;
  transition: border-color 0.18s ease;
}
.count-input:focus {
  border-color: var(--cinnabar);
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
  border-color: var(--mint);
  box-shadow: 0 0 0 2px var(--mint-wash);
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
  color: var(--danger-deep);
}

.start-btn {
  margin-top: 10px;
  appearance: none;
  padding: 12px 0;
  font-family: var(--serif);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.24em;
  color: #fff;
  background: var(--cinnabar);
  border: none;
  border-radius: 12px;
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
