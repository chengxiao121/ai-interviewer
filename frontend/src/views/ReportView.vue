<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getReport } from '@/api/assessment'
import { reportToMarkdown, downloadTextFile, reportFileName } from '@/utils/reportExport'
import type { AssessmentReport } from '@/types'
import html2pdf from 'html2pdf.js'

const route = useRoute()
const sessionId = computed(() => String(route.params.sessionId ?? ''))
const candidateId = computed(() => {
  const c = route.query.candidateId
  return typeof c === 'string' && c ? c : undefined
})

const report = ref<AssessmentReport | null>(null)
const loading = ref(false)
const error = ref('')
/** 首次生成说明（后端 ensure：第一次会现场调 LLM，可能数秒） */
const firstHint = ref(false)
const exportingPdf = ref(false)

async function load(force = false) {
  loading.value = true
  error.value = ''
  if (!report.value) firstHint.value = true
  try {
    report.value = await getReport(sessionId.value, candidateId.value, force)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '报告加载失败'
  } finally {
    loading.value = false
    firstHint.value = false
  }
}

onMounted(() => load())

/** 空报告判定：三类考点全空 = 该会话没有任何评分记录（聊了但没答/没评） */
const isEmpty = computed(() => {
  const r = report.value
  if (!r) return false
  return (r.coverage?.length ?? 0) === 0 && (r.outOfPlanTopics?.length ?? 0) === 0
})
/** 无考察计划：报告走降级版（仅实际统计） */
const hasPlan = computed(() => (report.value?.plan?.topics?.length ?? 0) > 0)

/** 分数徽标配色：≥7 达标绿，<4 告警红，其间中性 */
function scoreClass(score: number): string {
  if (score >= 7) return 'good'
  if (score < 4) return 'bad'
  return 'mid'
}

function exportMarkdown() {
  if (!report.value) return
  downloadTextFile(reportToMarkdown(report.value), reportFileName(report.value, 'md'))
}

/** PDF 导出：html2pdf 将报告 DOM 整体转 PDF（中文渲染跟随页面字体） */
async function exportPdf() {
  const el = document.getElementById('report-body')
  if (!el || exportingPdf.value) return
  exportingPdf.value = true
  try {
    await html2pdf()
      .set({
        margin: [10, 10, 12, 10],
        filename: reportFileName(report.value!, 'pdf'),
        image: { type: 'jpeg', quality: 0.96 },
        html2canvas: { scale: 2, useCORS: true, backgroundColor: '#ffffff' },
        jsPDF: { unit: 'mm', format: 'a4', orientation: 'portrait' },
        pagebreak: { mode: ['avoid-all', 'css', 'legacy'] },
      })
      .from(el)
      .save()
  } finally {
    exportingPdf.value = false
  }
}
</script>

<template>
  <div class="report-view">
    <header class="page-head">
      <div class="title-block">
        <p class="eyebrow">REPORT · 评估报告</p>
        <h2>面试评估报告</h2>
      </div>
      <div class="actions">
        <button class="btn" @click="load()" :disabled="loading">刷新</button>
        <button class="btn ink" :disabled="!report || isEmpty || exportingPdf" @click="exportMarkdown">
          导出 Markdown
        </button>
        <button class="btn ink" :disabled="!report || isEmpty || exportingPdf" @click="exportPdf">
          {{ exportingPdf ? '生成中…' : '导出 PDF' }}
        </button>
      </div>
    </header>
    <div class="page-rule"></div>

    <div class="report-wrap">
      <div v-if="loading" class="state-block">
        <div class="empty-seal">阅</div>
        <p>{{ firstHint ? '首次查看正在生成报告（含一次叙事生成，可能需要数秒）…' : '加载中…' }}</p>
      </div>
      <div v-else-if="error" class="state-block">
        <div class="empty-seal">误</div>
        <p class="error-text">{{ error }}</p>
        <button class="btn" @click="load()">重试</button>
      </div>
      <div v-else-if="!report || isEmpty" class="state-block">
        <div class="empty-seal">空</div>
        <p>该会话还没有评分记录——面试中完成作答并被评估官点评后，这里会生成报告。</p>
      </div>

      <div v-else id="report-body" class="report-body">
        <!-- 报告头 -->
        <div class="report-meta">
          <span class="meta-item">候选人 <b>{{ report.candidateId }}</b></span>
          <span class="meta-item">会话 <code :title="report.sessionId">{{ report.sessionId.slice(0, 13) }}</code></span>
          <span class="meta-item">生成于 <b>{{ report.generatedAt?.replace('T', ' ').slice(0, 16) }}</b></span>
          <span class="meta-item" title="报告是生成时刻的快照：跨会话薄弱点等段落在后续新面试后不会自动更新">
            ⓘ 快照报告
          </span>
        </div>
        <div v-if="!hasPlan" class="plan-note">该会话没有考察计划，以下为实际作答统计（降级版报告）。</div>

        <!-- 结论三件套 -->
        <section v-if="report.conclusion" class="conclusion">
          <div class="conclusion-row">
            <span class="stamp">总评</span>
            <p>{{ report.conclusion.overallSummary }}</p>
          </div>
          <div class="conclusion-row">
            <span class="stamp">匹配</span>
            <p>{{ report.conclusion.matchConclusion }}</p>
          </div>
          <div v-if="report.conclusion.recommendations?.length" class="conclusion-row recs">
            <span class="stamp">建议</span>
            <ul>
              <li v-for="(rec, i) in report.conclusion.recommendations" :key="i">{{ rec }}</li>
            </ul>
          </div>
        </section>

        <!-- 覆盖矩阵 -->
        <section v-if="report.coverage?.length" class="section">
          <h3>覆盖矩阵 · 计划考点</h3>
          <div v-for="item in report.coverage" :key="item.topic" class="coverage-card">
            <div class="coverage-head">
              <span class="topic">{{ item.topic }}</span>
              <span class="nums">
                <span class="num">{{ item.questionCount }} 题</span>
                <span class="num">均分 <b :class="scoreClass(item.avgScore)">{{ item.avgScore }}</b></span>
                <span class="num">最低 <b :class="scoreClass(item.minScore)">{{ item.minScore }}</b></span>
              </span>
            </div>
            <details v-if="item.evidences?.length" class="evidences">
              <summary>证据链（{{ item.evidences.length }} 条问答）</summary>
              <div v-for="(ev, i) in item.evidences" :key="i" class="evidence">
                <p class="q"><b>Q</b>{{ ev.question }}</p>
                <p class="a"><b>A</b>{{ ev.answerExcerpt }}</p>
                <p class="fb"><b :class="scoreClass(ev.score)">{{ ev.score }} 分</b>{{ ev.feedback }}</p>
              </div>
            </details>
          </div>
        </section>

        <!-- 未覆盖考点 -->
        <section v-if="report.uncoveredTopics?.length" class="section">
          <h3>未覆盖考点</h3>
          <p class="hint">计划了但本场没有考到——下一场可以点名让面试官重点考察。</p>
          <div class="chips">
            <span v-for="t in report.uncoveredTopics" :key="t" class="chip">{{ t }}</span>
          </div>
        </section>

        <!-- 计划外考点 -->
        <section v-if="report.outOfPlanTopics?.length" class="section">
          <h3>计划外考点</h3>
          <p class="hint">面试官现场追加的考点，如实呈现。</p>
          <div v-for="item in report.outOfPlanTopics" :key="item.topic" class="coverage-card">
            <div class="coverage-head">
              <span class="topic">{{ item.topic }}</span>
              <span class="nums">
                <span class="num">{{ item.questionCount }} 题</span>
                <span class="num">均分 <b :class="scoreClass(item.avgScore)">{{ item.avgScore }}</b></span>
              </span>
            </div>
          </div>
        </section>

        <!-- 跨会话薄弱点 -->
        <section v-if="report.crossSessionWeakness" class="section">
          <h3>跨会话薄弱点回顾</h3>
          <pre class="weakness">{{ report.crossSessionWeakness }}</pre>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.report-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  flex: 1;
  min-width: 0;
  position: relative;
  z-index: 1;
}
.report-wrap {
  flex: 1;
  overflow: auto;
  padding: 24px 32px 28px;
}
.report-body {
  max-width: 860px;
}

/* 报告头元信息 */
.report-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 22px;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: var(--paper-deep);
  border: 1px solid var(--rule);
  border-radius: 10px;
  font-size: 12.5px;
  color: var(--ink-soft);
}
.report-meta b {
  color: var(--ink-strong);
  font-weight: 600;
}
.report-meta code {
  font-family: var(--mono);
  font-size: 11.5px;
}
.plan-note {
  font-size: 12.5px;
  color: var(--ink-soft);
  border-left: 3px solid var(--rule-strong);
  padding: 4px 10px;
  margin-bottom: 16px;
}

/* 结论三件套 */
.conclusion {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 22px;
}
.conclusion-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.conclusion-row p,
.conclusion-row ul {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--ink);
  letter-spacing: 0.02em;
}
.conclusion-row ul {
  padding-left: 18px;
}
.stamp {
  flex-shrink: 0;
  width: 40px;
  height: 24px;
  font-family: var(--serif);
  font-size: 12px;
  font-weight: 600;
  color: var(--mint-deep);
  border: 1.5px solid var(--mint);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 1px;
}

/* 分区 */
.section {
  margin-bottom: 24px;
}
.section h3 {
  margin: 0 0 10px;
  font-family: var(--serif);
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--ink-strong);
  border-bottom: 1px solid var(--rule);
  padding-bottom: 8px;
}
.hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--ink-soft);
}

/* 覆盖矩阵卡片 */
.coverage-card {
  background: var(--paper-raise);
  border: 1px solid var(--rule);
  border-radius: 10px;
  padding: 12px 16px;
  margin-bottom: 10px;
}
.coverage-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.coverage-head .topic {
  font-family: var(--serif);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-strong);
}
.coverage-head .nums {
  margin-left: auto;
  display: flex;
  gap: 14px;
}
.num {
  font-size: 12px;
  color: var(--ink-soft);
}
.num b {
  font-weight: 600;
  color: var(--ink);
}
.num b.good {
  color: var(--mint-deep);
}
.num b.mid {
  color: var(--ink);
}
.num b.bad {
  color: var(--danger-deep);
}

/* 证据链折叠 */
.evidences {
  margin-top: 10px;
  border-top: 1px dashed var(--rule);
  padding-top: 8px;
}
.evidences summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--mint-deep);
  letter-spacing: 0.04em;
  user-select: none;
}
.evidence {
  padding: 10px 2px 2px;
}
.evidence p {
  margin: 0 0 6px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ink);
}
.evidence b {
  display: inline-block;
  margin-right: 8px;
  font-family: var(--mono);
  font-size: 10px;
  color: var(--ink-soft);
  border: 1px solid var(--rule-strong);
  border-radius: 4px;
  padding: 0 5px;
}
.evidence .fb b {
  color: var(--ink-strong);
}
.evidence .fb b.good {
  color: var(--mint-deep);
}
.evidence .fb b.bad {
  color: var(--danger-deep);
}

/* 未覆盖考点 chips */
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  font-size: 12.5px;
  color: var(--ink);
  background: var(--paper-deep);
  border: 1px dashed var(--rule-strong);
  border-radius: 999px;
  padding: 4px 12px;
}

/* 跨会话薄弱点（后端返回预格式化文本，原样呈现） */
.weakness {
  margin: 0;
  font-family: var(--mono);
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--ink);
  background: var(--paper-raise);
  border: 1px solid var(--rule);
  border-radius: 10px;
  padding: 14px 16px;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 状态块（加载/错误/空态） */
.state-block {
  padding: 70px 24px;
  text-align: center;
  color: var(--ink-soft);
  font-size: 13.5px;
  letter-spacing: 0.05em;
}
.state-block .error-text {
  color: var(--danger-deep);
  margin-bottom: 14px;
}
.empty-seal {
  width: 44px;
  height: 44px;
  margin: 0 auto 14px;
  font-family: var(--serif);
  font-size: 22px;
  font-weight: 600;
  color: var(--ink-soft);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
