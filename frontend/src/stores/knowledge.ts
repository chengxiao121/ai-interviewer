import { defineStore } from 'pinia'
import { ref } from 'vue'
import { syncKnowledge, listDocuments } from '@/api/knowledge'
import type { KnowledgeDoc } from '@/types'

/**
 * 知识库管理 store：文档列表、同步。
 */
export const useKnowledgeStore = defineStore('knowledge', () => {
  const documents = ref<KnowledgeDoc[]>([])
  const loading = ref(false)
  const syncing = ref(false)
  const lastSyncResult = ref<{ message: string; totalDocuments: number } | null>(null)
  const error = ref('')

  /** 拉取文档列表 */
  async function loadDocs() {
    loading.value = true
    error.value = ''
    try {
      documents.value = await listDocuments()
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  /** 触发同步，完成后刷新列表 */
  async function sync() {
    syncing.value = true
    error.value = ''
    try {
      lastSyncResult.value = await syncKnowledge()
      await loadDocs()
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      syncing.value = false
    }
  }

  return {
    documents,
    loading,
    syncing,
    lastSyncResult,
    error,
    loadDocs,
    sync,
  }
})
