// 统一 fetch 封装：base 路径 /api，默认 JSON，统一错误处理。
// 开发态经 Vite proxy 转发到后端 8080；生产态同源直连。

export const BASE = '/api'

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

/** GET JSON */
export async function getJson<T>(path: string): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    headers: { Accept: 'application/json' },
  })
  if (!resp.ok) {
    throw new ApiError(resp.status, `GET ${path} 失败：${resp.status}`)
  }
  return (await resp.json()) as T
}

/** POST（无返回体或 JSON 返回体） */
export async function postJson<T>(path: string, body?: unknown): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!resp.ok) {
    throw new ApiError(resp.status, `POST ${path} 失败：${resp.status}`)
  }
  // 允许空响应
  const text = await resp.text()
  return (text ? JSON.parse(text) : null) as T
}

/** DELETE */
export async function deleteJson<T>(path: string): Promise<T> {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  if (!resp.ok) {
    throw new ApiError(resp.status, `DELETE ${path} 失败：${resp.status}`)
  }
  const text = await resp.text()
  return (text ? JSON.parse(text) : null) as T
}

/** SSE POST 的 base url（供 utils/sse 使用） */
export function sseUrl(path: string): string {
  return `${BASE}${path}`
}
