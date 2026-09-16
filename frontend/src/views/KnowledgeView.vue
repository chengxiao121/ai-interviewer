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
    <header class="page-head">
      <div class="title-block">
        <p class="eyebrow">QUESTION BANK · 题库</p>
        <h2>知识库管理</h2>
      </div>
      <div class="actions">
        <button class="btn" @click="knowledgeStore.loadDocs()" :disabled="knowledgeStore.loading">
          刷新
        </button>
        <button class="btn ink" @click="knowledgeStore.sync()" :disabled="knowledgeStore.syncing">
          {{ knowledgeStore.syncing ? '誊录入库中…' : '同步知识库' }}
        </button>
      </div>
    </header>
    <div class="page-rule"></div>

    <div class="table-wrap">
      <div v-if="knowledgeStore.lastSyncResult" class="sync-result">
        <span class="mark">✓</span>
        {{ knowledgeStore.lastSyncResult.message }}，共 {{ knowledgeStore.lastSyncResult.totalDocuments }} 个文档
      </div>
      <div v-if="knowledgeStore.error" class="error-bar">
        <span class="mark">※</span>{{ knowledgeStore.error }}
      </div>

      <div class="paper-card">
        <DocTable :documents="knowledgeStore.documents" />
      </div>

      <div class="tip">
        <div class="tip-title">备注 · REMARKS</div>
        <p>同步会扫描外部目录 <code>./data/knowledges/</code> 与 classpath 兜底题库，幂等入库（内容哈希未变则跳过），并增量删除已移除的文档。</p>
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
.sync-result,
.error-bar {
  max-width: 960px;
  margin: 0 0 14px;
  padding: 11px 16px;
  border-radius: 4px;
  font-size: 13.5px;
  letter-spacing: 0.02em;
}
.sync-result {
  background: var(--green-wash);
  border-left: 3px solid var(--green);
  color: var(--green);
}
.error-bar {
  background: var(--cinnabar-wash);
  border-left: 3px solid var(--cinnabar);
  color: var(--cinnabar-deep);
}
.sync-result .mark,
.error-bar .mark {
  margin-right: 10px;
  font-weight: 700;
}
.paper-card {
  max-width: 960px;
  background: var(--paper-raise);
  border: 1px solid var(--rule-strong);
  border-radius: 6px;
  overflow: hidden;
  box-shadow: 0 3px 14px rgba(42, 36, 29, 0.07);
}
.tip {
  max-width: 960px;
  margin-top: 18px;
  padding: 14px 18px;
  border: 1px dashed var(--rule-strong);
  border-radius: 6px;
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
.tip code {
  font-family: var(--mono);
  font-size: 12px;
  background: var(--paper-deep);
  padding: 1px 7px;
  border-radius: 4px;
  color: var(--cinnabar-deep);
}
</style>
