<script setup lang="ts">
import { onMounted } from 'vue'
import { useKnowledgeStore } from '@/stores/knowledge'
import DocTable from '@/components/DocTable.vue'

const knowledgeStore = useKnowledgeStore()

onMounted(() => {
  knowledgeStore.loadDocs()
})
</script>

<template>
  <div class="knowledge-view">
    <header class="page-header">
      <h2>知识库管理</h2>
      <div class="actions">
        <button class="btn" @click="knowledgeStore.loadDocs()" :disabled="knowledgeStore.loading">
          刷新
        </button>
        <button class="btn primary" @click="knowledgeStore.sync()" :disabled="knowledgeStore.syncing">
          {{ knowledgeStore.syncing ? '同步中…' : '同步知识库' }}
        </button>
      </div>
    </header>

    <div v-if="knowledgeStore.lastSyncResult" class="sync-result">
      ✅ {{ knowledgeStore.lastSyncResult.message }}，共 {{ knowledgeStore.lastSyncResult.totalDocuments }} 个文档
    </div>
    <div v-if="knowledgeStore.error" class="error-bar">{{ knowledgeStore.error }}</div>

    <div class="table-wrap">
      <DocTable :documents="knowledgeStore.documents" />
    </div>

    <div class="tip">
      <p>说明：同步会扫描外部目录 <code>./data/knowledges/</code> 与 classpath 兜底题库，幂等入库（内容哈希未变则跳过），并增量删除已移除的文档。</p>
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
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--color-border);
}
.page-header h2 { margin: 0; font-size: 16px; }
.actions { display: flex; gap: 10px; }
.btn {
  padding: 8px 16px;
  border-radius: 8px;
  border: 1px solid var(--color-border);
  background: var(--color-bg);
  cursor: pointer;
  font-size: 14px;
}
.btn:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.primary {
  background: var(--color-primary);
  color: #fff;
  border-color: var(--color-primary);
}
.sync-result {
  margin: 12px 20px 0;
  padding: 10px 14px;
  background: rgba(34, 197, 94, 0.1);
  color: #16a34a;
  border-radius: 8px;
  font-size: 14px;
}
.error-bar {
  margin: 12px 20px 0;
  padding: 10px 14px;
  background: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
  border-radius: 8px;
  font-size: 14px;
}
.table-wrap {
  flex: 1;
  overflow: auto;
  padding: 16px 20px;
}
.tip {
  padding: 12px 20px;
  border-top: 1px solid var(--color-border);
  color: var(--color-text-muted);
  font-size: 13px;
}
.tip code {
  background: var(--color-bg-soft);
  padding: 1px 6px;
  border-radius: 4px;
}
</style>
