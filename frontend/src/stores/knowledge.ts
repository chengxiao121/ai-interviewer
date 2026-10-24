import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  listKnowledgeBases,
  previewQuestions,
} from '@/api/knowledgeBase'
import type { CreateKnowledgeBaseResult, KnowledgeBaseDto, QuestionPreviewDto } from '@/types'

/**
 * 知识库（题库）管理 store（阶段 12 重写）：
 * 上传新建（解析统计回显）/ 列表 / 删除 / 题目预览。
 * 旧的"同步目录"链路（syncKnowledge/listDocuments）已移除调用，接口本身在阶段 7 删除。
 */
export const useKnowledgeStore = defineStore('knowledge', () => {
  const bases = ref<KnowledgeBaseDto[]>([])
  const loading = ref(false)
  const creating = ref(false)
  const error = ref('')
  /** 最近一次创建结果（解析统计：成功 N 题 / 跳过 M 道），成功横幅展示用 */
  const lastCreated = ref<CreateKnowledgeBaseResult | null>(null)
  /** 题目预览缓存：kbId → 前 N 道题（展开卡片时拉取） */
  const previews = ref<Record<number, QuestionPreviewDto[]>>({})

  /** 拉取知识库列表 */
  async function loadBases() {
    loading.value = true
    error.value = ''
    try {
      bases.value = await listKnowledgeBases()
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      loading.value = false
    }
  }

  /**
   * 上传文档新建知识库。成功后刷新列表并回显解析统计；
   * 失败（重名/格式/0 题）→ error 置为后端中文提示，不建库。
   */
  async function create(file: File, name: string, description?: string) {
    creating.value = true
    error.value = ''
    try {
      const content = await file.text()
      lastCreated.value = await createKnowledgeBase(name, file.name, content, description)
      await loadBases()
      return lastCreated.value
    } catch (e) {
      error.value = (e as Error).message
      return null
    } finally {
      creating.value = false
    }
  }

  /** 删除知识库（confirm 由视图层负责），成功后刷新列表 */
  async function remove(id: number) {
    error.value = ''
    try {
      await deleteKnowledgeBase(id)
      delete previews.value[id]
      if (lastCreated.value?.knowledgeBase.id === id) {
        lastCreated.value = null
      }
      await loadBases()
      return true
    } catch (e) {
      error.value = (e as Error).message
      return false
    }
  }

  /** 题目预览（前 20 道，按库内原序）；已拉取过直接用缓存 */
  async function loadPreview(id: number) {
    if (previews.value[id]) return
    try {
      previews.value[id] = await previewQuestions(id, 20)
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  return {
    bases,
    loading,
    creating,
    error,
    lastCreated,
    previews,
    loadBases,
    create,
    remove,
    loadPreview,
  }
})
