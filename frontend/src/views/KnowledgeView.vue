<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useKnowledgeStore } from '@/stores/knowledge'
import { isAllowedQuestionFile } from '@/api/knowledgeBase'
import type { KnowledgeBaseDto } from '@/types'

/**
 * 知识库（题库）管理页（阶段 12 重做）：
 * 上传新建（【考点】+【参考答案】格式 txt/md，原文不保存）/ 列表 / 删除 / 题目预览 / 空状态引导。
 * 旧的"同步目录 + 文档块数表格"已随向量链路废弃（接口在阶段 7 删除）。
 */
const knowledgeStore = useKnowledgeStore()

// ===== 上传新建 =====
const fileInputRef = ref<HTMLInputElement | null>(null)
const pendingFile = ref<File | null>(null)
const kbName = ref('')
const kbDescription = ref('')
const formError = ref('')

onMounted(() => {
  knowledgeStore.loadBases()
})

function onFileChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  formError.value = ''
  if (!file) return
  if (!isAllowedQuestionFile(file)) {
    formError.value = `「${file.name}」不是 txt/md 文本文件`
    return
  }
  pendingFile.value = file
  // 名称缺省取文件名去扩展名（可改）
  if (!kbName.value.trim()) {
    kbName.value = file.name.replace(/\.(txt|md|markdown)$/i, '')
  }
}

async function submitCreate() {
  formError.value = ''
  if (!pendingFile.value) {
    formError.value = '请先选择题库文档（txt/md）'
    return
  }
  if (!kbName.value.trim()) {
    formError.value = '请填写知识库名称'
    return
  }
  const result = await knowledgeStore.create(pendingFile.value, kbName.value, kbDescription.value)
  if (result) {
    // 成功后清空表单，横幅展示解析统计
    pendingFile.value = null
    kbName.value = ''
    kbDescription.value = ''
  }
}

/** 分类分布 → 展示片段："集合 3 · 并发 3 · 未分类 2" */
function distributionLabel(kb: KnowledgeBaseDto): string {
  const entries = Object.entries(kb.categoryDistribution ?? {})
  if (entries.length === 0) return ''
  return entries
    .map(([cat, n]) => `${cat ?? '未分类'} ${n}`)
    .join(' · ')
}

// ===== 删除 =====
const deletingId = ref<number | null>(null)
async function removeKb(kb: KnowledgeBaseDto) {
  if (!window.confirm(`确认删除知识库「${kb.name}」？其下 ${kb.questionCount} 道题将一并删除，此操作不可恢复。`)) {
    return
  }
  deletingId.value = kb.id
  await knowledgeStore.remove(kb.id)
  deletingId.value = null
}

// ===== 题目预览 =====
/** 展开的预览卡片（kbId → true），展开时懒加载题目 */
const expanded = ref<Record<number, boolean>>({})
function togglePreview(kb: KnowledgeBaseDto) {
  expanded.value[kb.id] = !expanded.value[kb.id]
  if (expanded.value[kb.id]) {
    knowledgeStore.loadPreview(kb.id)
  }
}

function fmtDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso.slice(0, 10)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
</script>

<template>
  <div class="knowledge-view">
    <header class="page-head">
      <div class="title-block">
        <p class="eyebrow">KNOWLEDGE · 知识库</p>
        <h2>题库管理</h2>
      </div>
      <div class="actions">
        <button class="btn" @click="knowledgeStore.loadBases()" :disabled="knowledgeStore.loading">
          刷新
        </button>
      </div>
    </header>
    <div class="page-rule"></div>

    <div class="table-wrap">
      <!-- 上传新建 -->
      <div class="paper-card upload-card">
        <div class="card-title">上传题库文档</div>
        <p class="upload-sub">
          文档按「<code>【考点】题目…</code> + <code>【参考答案】…</code>」空行分隔的格式解析切题，
          <code>##</code> 二级标题为分类。解析后原文不保存，需要更新重新上传即可。
        </p>
        <div class="upload-form">
          <button class="upload-btn" :disabled="knowledgeStore.creating" @click="fileInputRef?.click()">
            {{ pendingFile ? `✓ 已选择：${pendingFile.name}` : '＋ 选择题库文档（txt/md）' }}
          </button>
          <input ref="fileInputRef" type="file" accept=".txt,.md,.markdown" hidden @change="onFileChosen" />
          <input v-model="kbName" class="name-input" type="text" placeholder="知识库名称（必填，不可与已有库重名）" maxlength="64" />
          <input v-model="kbDescription" class="desc-input" type="text" placeholder="描述（可选，备注这个库考什么）" maxlength="255" />
          <button class="btn ink" :disabled="knowledgeStore.creating || !pendingFile || !kbName.trim()" @click="submitCreate">
            {{ knowledgeStore.creating ? '解析入库中…' : '创建知识库' }}
          </button>
        </div>
        <p v-if="formError" class="form-err">{{ formError }}</p>
      </div>

      <!-- 创建结果横幅（解析统计，让用户自查"少了几道题"） -->
      <div v-if="knowledgeStore.lastCreated" class="create-result">
        <span class="mark">✓</span>
        知识库「{{ knowledgeStore.lastCreated.knowledgeBase.name }}」创建成功：解析出
        {{ knowledgeStore.lastCreated.parsedCount }} 道题<template v-if="knowledgeStore.lastCreated.skippedCount > 0">
          ，另有 {{ knowledgeStore.lastCreated.skippedCount }} 道因缺【参考答案】被跳过（可点开预览自查）</template>。
      </div>

      <div v-if="knowledgeStore.error" class="error-bar">
        <span class="mark">※</span>{{ knowledgeStore.error }}
      </div>

      <!-- 列表 -->
      <div v-if="knowledgeStore.loading && knowledgeStore.bases.length === 0" class="loading">加载中…</div>

      <!-- 空状态引导：入场页的选择器与这里共用"先有库才有题库面试"的前提 -->
      <div v-else-if="knowledgeStore.bases.length === 0" class="empty">
        <div class="empty-seal">空</div>
        <p class="empty-title">还没有知识库</p>
        <p class="empty-desc">在上方上传一个「【考点】+【参考答案】」格式的题库文档（txt/md），<br />创建后即可在面试入场时选择它进行背题自检。</p>
      </div>

      <div v-else class="kb-list">
        <div v-for="kb in knowledgeStore.bases" :key="kb.id" class="paper-card kb-card">
          <div class="kb-head">
            <span class="kb-badge">题库</span>
            <span class="kb-name" :title="kb.name">{{ kb.name }}</span>
            <span class="kb-count">{{ kb.questionCount }} 题</span>
            <span class="kb-actions">
              <button class="btn small" @click="togglePreview(kb)">
                {{ expanded[kb.id] ? '收起题目' : '预览题目' }}
              </button>
              <button class="btn small danger" :disabled="deletingId === kb.id" @click="removeKb(kb)">
                {{ deletingId === kb.id ? '删除中…' : '删除' }}
              </button>
            </span>
          </div>
          <div class="kb-meta">
            <span v-if="kb.description" class="kb-desc" :title="kb.description">{{ kb.description }}</span>
            <span v-if="distributionLabel(kb)" class="kb-dist">{{ distributionLabel(kb) }}</span>
            <span class="kb-src">
              {{ kb.sourceFileName ?? '未知来源' }} · {{ fmtDate(kb.createdAt) }}
            </span>
          </div>

          <!-- 题目预览（懒加载，前 20 道） -->
          <div v-if="expanded[kb.id]" class="preview-box">
            <div v-if="!knowledgeStore.previews[kb.id]" class="loading">加载题目中…</div>
            <ol v-else class="preview-list">
              <li v-for="q in knowledgeStore.previews[kb.id]" :key="q.seq" class="preview-item">
                <div class="q-stem">
                  <span class="q-seq">{{ q.seq }}</span>
                  <span v-if="q.category" class="q-cat">{{ q.category }}</span>
                  <span class="q-text">{{ q.stem }}</span>
                </div>
                <div class="q-answer" :title="q.referenceAnswer">参考答案：{{ q.referenceAnswer }}</div>
              </li>
            </ol>
          </div>
        </div>
      </div>

      <div class="tip">
        <div class="tip-title">备注 · REMARKS</div>
        <p>
          题库面试（背题自检）在「面试对话」入场时选择模式「知识库面试」并指定题库与题数；
          组卷从该库随机抽题，每场面试一张固定卷子。删除知识库不影响历史面试与报告。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  flex: 1;
  min-width: 0;
  position: relative;
  z-index: 1;
}
.table-wrap {
  flex: 1;
  overflow: auto;
  padding: 24px 32px 20px;
}
.create-result,
.error-bar {
  max-width: 960px;
  margin: 0 0 14px;
  padding: 11px 16px;
  border-radius: 4px;
  font-size: 13.5px;
  letter-spacing: 0.02em;
}
.create-result {
  background: var(--green-wash);
  border-left: 3px solid var(--green);
  color: var(--green);
}
.error-bar {
  background: var(--danger-wash);
  border-left: 3px solid var(--danger);
  color: var(--danger-deep);
}
.create-result .mark,
.error-bar .mark {
  margin-right: 10px;
  font-weight: 700;
}

.paper-card {
  max-width: 960px;
  background: var(--paper-raise);
  border: 1px solid var(--rule);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 3px 14px rgba(31, 40, 35, 0.06);
}

/* 上传表单 */
.upload-card {
  padding: 16px 20px;
  margin-bottom: 16px;
}
.card-title {
  font-family: var(--serif);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--ink-strong);
}
.upload-sub {
  margin: 8px 0 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ink-soft);
}
.upload-sub code {
  font-family: var(--mono);
  font-size: 11.5px;
  background: var(--paper-deep);
  padding: 1px 6px;
  border-radius: 4px;
  color: var(--cinnabar-deep);
}
.upload-form {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.upload-btn {
  appearance: none;
  padding: 8px 14px;
  font-size: 13px;
  letter-spacing: 0.05em;
  color: var(--ink);
  background: var(--paper-deep);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 4px;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;
}
.upload-btn:hover:not(:disabled) {
  border-color: var(--cinnabar);
  color: var(--cinnabar);
}
.name-input,
.desc-input {
  padding: 8px 12px;
  font-size: 13px;
  font-family: var(--sans);
  color: var(--ink-strong);
  background: var(--paper-deep);
  border: 1.5px solid var(--rule-strong);
  border-radius: 4px;
  outline: none;
  transition: border-color 0.15s ease;
}
.name-input {
  flex: 0 1 240px;
}
.desc-input {
  flex: 1 1 220px;
}
.name-input:focus,
.desc-input:focus {
  border-color: var(--cinnabar);
}
.form-err {
  margin: 10px 0 0;
  font-size: 12.5px;
  color: var(--danger-deep);
}

/* 列表 */
.kb-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.kb-card {
  padding: 14px 20px;
}
.kb-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.kb-badge {
  font-family: var(--mono);
  font-size: 8.5px;
  letter-spacing: 0.14em;
  color: var(--cinnabar);
  border: 1px solid var(--cinnabar);
  border-radius: 3px;
  padding: 1px 5px;
  flex-shrink: 0;
}
.kb-name {
  font-family: var(--serif);
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--ink-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-count {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--mint-deep);
  flex-shrink: 0;
}
.kb-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.kb-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ink-soft);
  letter-spacing: 0.02em;
}
.kb-desc {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}
.kb-dist {
  color: var(--ink-blue);
}
.kb-src {
  margin-left: auto;
  font-family: var(--mono);
  font-size: 10.5px;
  flex-shrink: 0;
}

/* 题目预览 */
.preview-box {
  margin-top: 12px;
  border-top: 1px dashed var(--rule-strong);
  padding-top: 10px;
}
.preview-list {
  margin: 0;
  padding: 0 0 0 4px;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.preview-item {
  font-size: 12.5px;
  line-height: 1.65;
}
.q-stem {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.q-seq {
  font-family: var(--mono);
  font-size: 10.5px;
  color: var(--ink-soft);
  flex-shrink: 0;
}
.q-cat {
  font-family: var(--mono);
  font-size: 9.5px;
  color: var(--ink-blue);
  border: 1px solid var(--ink-blue);
  border-radius: 3px;
  padding: 0 5px;
  flex-shrink: 0;
}
.q-text {
  color: var(--ink);
  font-family: var(--kai);
}
.q-answer {
  margin-top: 2px;
  padding-left: 26px;
  color: var(--ink-soft);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 空状态引导 */
.empty {
  max-width: 960px;
  padding: 48px 24px;
  text-align: center;
  background: var(--paper-raise);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 14px;
}
.empty-seal {
  width: 48px;
  height: 48px;
  margin: 0 auto 14px;
  font-family: var(--serif);
  font-size: 24px;
  font-weight: 600;
  color: var(--ink-soft);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.empty-title {
  margin: 0;
  font-family: var(--serif);
  font-size: 17px;
  letter-spacing: 0.12em;
  color: var(--ink);
}
.empty-desc {
  margin: 10px 0 0;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-soft);
}

.loading,
.or {
  font-size: 12.5px;
  color: var(--ink-soft);
  letter-spacing: 0.08em;
  margin: 6px 0;
}

.btn.small {
  padding: 4px 12px;
  font-size: 12px;
}
.btn.danger {
  color: var(--danger-deep);
  border-color: var(--danger);
}
.btn.danger:hover:not(:disabled) {
  background: var(--danger-wash);
}

.tip {
  max-width: 960px;
  margin-top: 18px;
  padding: 14px 18px;
  border: 1px dashed var(--rule-strong);
  border-radius: 12px;
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 1.7;
}
.tip-title {
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.26em;
  color: var(--cinnabar);
  margin-bottom: 6px;
}
</style>
