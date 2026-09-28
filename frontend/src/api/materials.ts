// 上传资料 API：JD/简历文件上传，返回 materialId 供聊天请求引用。
// 类型自动识别：文件名含简历/resume/cv → RESUME，其余（JD/岗位/职位/未识别）→ JD，
// 识别错了用户可在资料标签上点击切换（切换 = 用另一类型重新上传，服务端按类型+内容哈希去重）。

import { BASE } from './request'
import type { MaterialUploadResult } from '@/types'

export type MaterialType = 'JD' | 'RESUME'

/** 允许的扩展名（与后端白名单一致） */
const ALLOWED_EXT = ['txt', 'md', 'markdown']

/** 按文件名推断资料类型（识别不了默认 JD——最常见的演示场景，用户可点标签切换） */
export function detectMaterialType(fileName: string): MaterialType {
  const lower = fileName.toLowerCase()
  if (lower.includes('简历') || lower.includes('resume') || /(^|[^a-z])cv([^a-z]|$)/.test(lower)) {
    return 'RESUME'
  }
  return 'JD'
}

/** 扩展名校验（上传前快速失败，提示更友好） */
export function isAllowedMaterialFile(file: File): boolean {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
  return ALLOWED_EXT.includes(ext)
}

/** 上传资料文件（读出文本后以纯文本体直传——后端是 WebFlux，multipart 不可用） */
export async function uploadMaterial(file: File, type: MaterialType): Promise<MaterialUploadResult> {
  const content = await file.text()
  const params = new URLSearchParams({ type, name: file.name })
  const resp = await fetch(`${BASE}/materials/upload?${params.toString()}`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    body: content,
  })
  if (!resp.ok) {
    // 后端 400 带中文原因（类型识别失败/空文件等），尽量透传
    let detail = `${resp.status}`
    try {
      const text = await resp.text()
      if (text) detail = extractDetail(text) || detail
    } catch {
      /* 忽略读取失败 */
    }
    throw new Error(`上传失败：${detail}`)
  }
  return (await resp.json()) as MaterialUploadResult
}

/** 从 Spring 默认错误体里抠出 message 字段 */
function extractDetail(text: string): string {
  try {
    const parsed = JSON.parse(text) as { message?: string }
    return parsed.message ?? ''
  } catch {
    return ''
  }
}
