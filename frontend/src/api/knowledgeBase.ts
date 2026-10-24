// 知识库（题库）管理 API（阶段 12）：上传新建 / 列表 / 删除 / 题目预览。
// 上传沿用项目「纯文本体」约定（后端是 WebFlux，multipart 不可用）：
// 前端 file.text() 读出后以 text/plain 直传，元信息（name/description/fileName）走 query 参数。
// 旧的 /api/knowledge 同步接口在阶段 7 随向量链路一起删除，本文件是唯一新入口。

import { BASE, getJson } from './request'
import type { CreateKnowledgeBaseResult, KnowledgeBaseDto, QuestionPreviewDto } from '@/types'

/** 允许的扩展名（与后端白名单一致） */
const ALLOWED_EXT = ['txt', 'md', 'markdown']

/** 扩展名校验（上传前快速失败） */
export function isAllowedQuestionFile(file: File): boolean {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
  return ALLOWED_EXT.includes(ext)
}

/** 从后端错误体抠中文 message（GlobalExceptionHandler 统一返回 {code, message, traceId}） */
async function extractError(resp: Response, fallback: string): Promise<string> {
  try {
    const parsed = (await resp.json()) as { message?: string }
    if (parsed.message) return parsed.message
  } catch {
    /* 非 JSON 错误体，走兜底 */
  }
  return `${fallback}（HTTP ${resp.status}）`
}

/**
 * 新建知识库：解析「【考点】+【参考答案】」格式切题入库（原文不保存）。
 * 失败（重名 / 非 txt/md / 解析 0 题 / 空文件）→ Error(message 为后端中文提示)。
 */
export async function createKnowledgeBase(
  name: string,
  fileName: string,
  content: string,
  description?: string,
): Promise<CreateKnowledgeBaseResult> {
  const params = new URLSearchParams({ name })
  if (description && description.trim()) params.set('description', description.trim())
  if (fileName) params.set('fileName', fileName)
  const resp = await fetch(`${BASE}/knowledge-bases?${params.toString()}`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    body: content,
  })
  if (!resp.ok) {
    throw new Error(await extractError(resp, '创建知识库失败'))
  }
  return (await resp.json()) as CreateKnowledgeBaseResult
}

/** 知识库列表（含题目数与分类分布，按创建时间倒序）——入场选择器与管理页共用 */
export function listKnowledgeBases(): Promise<KnowledgeBaseDto[]> {
  return getJson<KnowledgeBaseDto[]>('/knowledge-bases')
}

/** 题目预览（前 limit 道按原序，默认 20）——上传后自查解析质量 */
export function previewQuestions(id: number, limit?: number): Promise<QuestionPreviewDto[]> {
  const q = limit && limit > 0 ? `?limit=${limit}` : ''
  return getJson<QuestionPreviewDto[]>(`/knowledge-bases/${id}/questions${q}`)
}

/** 删除知识库（连带删题；历史会话不受影响，卡片库名降级） */
export async function deleteKnowledgeBase(id: number): Promise<void> {
  const resp = await fetch(`${BASE}/knowledge-bases/${id}`, { method: 'DELETE' })
  if (!resp.ok) {
    throw new Error(await extractError(resp, '删除知识库失败'))
  }
}
