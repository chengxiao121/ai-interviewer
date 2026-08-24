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
</script>

<template>
  <table class="doc-table">
    <thead>
      <tr>
        <th>文件名</th>
        <th>块数</th>
        <th>状态</th>
        <th>来源</th>
        <th>入库时间</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="doc in documents" :key="doc.fileName">
        <td class="name">{{ doc.fileName }}</td>
        <td>{{ doc.chunkCount }}</td>
        <td>
          <span class="status" :class="doc.status.toLowerCase()">{{ doc.status }}</span>
        </td>
        <td>{{ doc.source }}</td>
        <td class="time">{{ formatTime(doc.ingestedAt) }}</td>
      </tr>
      <tr v-if="documents.length === 0">
        <td colspan="5" class="empty">暂无文档，点击上方「同步知识库」入库</td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.doc-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}
.doc-table th,
.doc-table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}
.doc-table th {
  color: var(--color-text-muted);
  font-weight: 600;
  background: var(--color-bg-soft);
}
.name { word-break: break-all; }
.time { color: var(--color-text-muted); font-size: 13px; }
.empty {
  text-align: center;
  color: var(--color-text-muted);
  padding: 32px;
}
.status {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}
.status.ingested {
  background: rgba(34, 197, 94, 0.15);
  color: #16a34a;
}
.status.failed {
  background: rgba(239, 68, 68, 0.15);
  color: var(--color-danger);
}
</style>
