import { getJson, postJson } from './request'
import type { SyncResult, KnowledgeDoc } from '@/types'

/** 手动触发知识库同步 */
export function syncKnowledge(): Promise<SyncResult> {
  return postJson<SyncResult>('/knowledge/sync')
}

/** 查看文档元数据列表 */
export function listDocuments(): Promise<KnowledgeDoc[]> {
  return getJson<KnowledgeDoc[]>('/knowledge/documents')
}
