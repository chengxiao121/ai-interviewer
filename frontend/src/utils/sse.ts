// SSE 解析器：后端 chat 是 POST + text/event-stream，
// EventSource 不支持 POST，故用 fetch + ReadableStream 手解 SSE 帧。
//
// SSE 帧格式：
//   data: 文本片段\n
//   \n          （空行分隔帧）
// 后端 Flux<String> 经 Spring 的 ServerSentEvent 包装后，每片为一条 data: 行。
// 本解析器逐块读取、按 \n\n 分帧、取 data: 内容回调 onChunk。

export interface SseOptions {
  /** 请求地址 */
  url: string
  /** 请求体（POST body） */
  body: unknown
  /** 收到一片文本时回调 */
  onChunk: (text: string) => void
  /** 流正常结束时回调 */
  onDone?: () => void
  /** 出错时回调 */
  onError?: (err: Error) => void
  /** 额外请求头 */
  headers?: Record<string, string>
}

/**
 * 发起 POST SSE 请求，逐片回调文本。
 * @returns AbortController，可调用 .abort() 中断生成
 */
export function postSse(opts: SseOptions): AbortController {
  const controller = new AbortController()

  fetch(opts.url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...opts.headers,
    },
    body: JSON.stringify(opts.body),
    signal: controller.signal,
  })
    .then(async (resp) => {
      if (!resp.ok || !resp.body) {
        throw new Error(`SSE 请求失败：${resp.status} ${resp.statusText}`)
      }
      const reader = resp.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      // 持续读取流
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        // 按 \n\n 分帧（SSE 帧分隔符）
        let sepIndex: number
        while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
          const frame = buffer.slice(0, sepIndex)
          buffer = buffer.slice(sepIndex + 2)
          parseFrame(frame, opts.onChunk)
        }
      }
      // 处理尾部残留
      if (buffer.trim()) parseFrame(buffer, opts.onChunk)
      opts.onDone?.()
    })
    .catch((err) => {
      // 主动 abort 不算错误
      if (err?.name === 'AbortError') {
        opts.onDone?.()
        return
      }
      opts.onError?.(err instanceof Error ? err : new Error(String(err)))
    })

  return controller
}

/** 解析单个 SSE 帧，提取 data: 行内容回调 */
function parseFrame(frame: string, onChunk: (text: string) => void): void {
  const lines = frame.split('\n')
  // SSE 规范：同一个事件里多条 data: 行要用 \n 拼接成一个完整 data 字段。
  // Spring 写出器（writeStringData）会把分片内的换行自动转成 "\ndata:" 续行，
  // 这里按规范拼接即可原样还原换行。
  // （旧实现逐行回调、丢弃其余行，导致换行被吞、回答挤成一段。）
  const dataLines: string[] = []
  for (const line of lines) {
    if (line.startsWith('data:')) {
      // data: 后面的内容，去掉一个前导空格（SSE 规范：data: 后可选一个空格）
      let data = line.slice(5)
      if (data.startsWith(' ')) data = data.slice(1)
      dataLines.push(data)
    }
  }
  if (dataLines.length > 0) {
    onChunk(dataLines.join('\n'))
  }
}
