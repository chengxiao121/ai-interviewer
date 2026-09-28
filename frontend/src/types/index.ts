// 前后端共享类型，与后端 DTO 一一对应

/** 聊天消息（前端统一用小写 role 便于渲染判断） */
export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

/** 后端历史消息 DTO：{role, content}，role 为 MessageType.name()（USER/ASSISTANT） */
export interface HistoryMessageDto {
  role: string
  content: string
}

/** 后端清空会话结果 */
export interface ClearResult {
  sessionId: string
  cleared: boolean
}

/** 后端知识库同步结果 */
export interface SyncResult {
  message: string
  totalDocuments: number
}

/** 文档元数据 */
export interface KnowledgeDoc {
  fileName: string
  chunkCount: number
  status: string // INGESTED / FAILED
  source: string // EXTERNAL / CLASSPATH
  ingestedAt: string // ISO 字符串
}

/** 上传资料（JD/简历）结果：materialId 供聊天请求引用 */
export interface MaterialUploadResult {
  materialId: string
  type: 'JD' | 'RESUME'
  fileName: string
  charCount: number
  duplicated: boolean
}

/** 历史简历元数据（GET /api/materials/resumes，入场页"继续之前的简历"用） */
export interface ResumeMeta {
  materialId: string
  type: string
  fileName: string
  md5: string
  charCount: number
  createdAt: string
}

/** 面试会话绑定（GET /api/interview-sessions）：会话与候选人/资料的持久关系 */
export interface InterviewSessionDto {
  sessionId: string
  candidateId: string
  materialIds: string[]
  createdAt: string
}
