<script setup lang="ts">
import type { KnowledgeDoc } from '@/types'

defineProps<{
  documents: KnowledgeDoc[]
}>()

function formatTime(iso: string): string {
  if (!iso) return '-'
  try {
    return new Date(iso).toLocaleString('zh-CN')
  } catch {
    return iso
  }
}

/** 状态中文批注 */
const statusLabel: Record<string, string> = {
  INGESTED: '已入库',
  FAILED: '未通过',
}
</script>

<template>
  <table class="doc-table">
    <thead>
      <tr>
        <th>文件名 · DOCUMENT</th>
        <th>块数 · CHUNKS</th>
        <th>状态 · STATUS</th>
        <th>来源 · SOURCE</th>
        <th>入库时间 · INGESTED AT</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="doc in documents" :key="doc.fileName">
        <td class="name">{{ doc.fileName }}</td>
        <td class="mono">{{ doc.chunkCount }}</td>
        <td>
          <span class="status" :class="doc.status.toLowerCase()">
            {{ statusLabel[doc.status.toUpperCase()] ?? doc.status }}
          </span>
        </td>
        <td class="mono source">{{ doc.source }}</td>
        <td class="time">{{ formatTime(doc.ingestedAt) }}</td>
      </tr>
      <tr v-if="documents.length === 0">
        <td colspan="5" class="empty">暂无文档 —— 点击上方「同步知识库」完成入库</td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.doc-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13.5px;
}
.doc-table th,
.doc-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--rule);
}
.doc-table th {
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.16em;
  font-weight: 600;
  color: var(--ink-soft);
  background: var(--paper-deep);
  white-space: nowrap;
}
.doc-table tbody tr {
  transition: background 0.15s ease;
}
.doc-table tbody tr:hover {
  background: rgba(42, 36, 29, 0.03);
}
.doc-table tbody tr:last-child td {
  border-bottom: none;
}
.name {
  word-break: break-all;
  font-weight: 600;
  color: var(--ink-strong);
}
.mono {
  font-family: var(--mono);
  font-size: 12.5px;
}
.source {
  letter-spacing: 0.08em;
  color: var(--ink-soft);
}
.time {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--ink-soft);
  white-space: nowrap;
}
.empty {
  text-align: center;
  color: var(--ink-soft);
  padding: 40px 20px;
  letter-spacing: 0.08em;
}
/* 状态标签 */
.status {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 4px;
  font-family: var(--serif);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.14em;
}
.status.ingested {
  color: var(--green);
  border: 1.5px solid var(--green);
  background: var(--green-wash);
}
.status.failed {
  color: var(--cinnabar);
  border: 1.5px solid var(--cinnabar);
  background: var(--cinnabar-wash);
}
</style>
