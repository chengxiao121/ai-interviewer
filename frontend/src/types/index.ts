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

/** 后端清空会话结果：cleared = 两步全部成功（Redis 记忆 + PG 入场绑定各自独立汇报） */
export interface ClearResult {
  sessionId: string
  cleared: boolean
  memoryCleared: boolean
  bindingCleared: boolean
}

// （旧向量链路的 SyncResult / KnowledgeDoc 已随阶段 7 删除；知识库相关类型见下方"知识库"节）

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

/** 会话生命周期状态（阶段 11）：进行中可答题；已结束只答疑、报告才可看 */
export type SessionStatus = 'IN_PROGRESS' | 'FINISHED'

/** 面试模式（阶段 12）：入场时绑定、不可切换 */
export type InterviewMode = 'MATERIAL' | 'KNOWLEDGE'

/** 面试会话绑定（GET /api/interview-sessions）：会话与候选人/资料的持久关系 */
export interface InterviewSessionDto {
  sessionId: string
  candidateId: string
  materialIds: string[]
  createdAt: string
  /** 绑定的简历文件名（元数据读取失败时为 null，展示用） */
  resumeFileName?: string | null
  /** 绑定的 JD 文件名（可为 null：JD 是可选入场项） */
  jdFileName?: string | null
  /** 生命周期状态：报告入口按此门禁（FINISHED 才可看报告） */
  status: SessionStatus
  /** 面试模式（阶段 12）：MATERIAL=资料面试 / KNOWLEDGE=知识库面试 */
  mode: InterviewMode
  /** 绑定的知识库 id（仅 KNOWLEDGE 模式有值） */
  kbId?: number | null
  /** 知识库名（库被删除后为 null，展示降级） */
  kbName?: string | null
}

// ===== 知识库（阶段 12：题库上传入库，替代目录同步）=====

/** 知识库（GET /api/knowledge-bases 列表项 / 新建结果） */
export interface KnowledgeBaseDto {
  id: number
  name: string
  description?: string | null
  /** ACTIVE 题目数（组卷容量上限的直接依据） */
  questionCount: number
  /** 分类 → 题目数（如 {集合:3, 并发:3}） */
  categoryDistribution: Record<string, number>
  sourceFileName?: string | null
  createdAt: string
}

/** 题目预览条目（GET /api/knowledge-bases/{id}/questions） */
export interface QuestionPreviewDto {
  seq: number
  category?: string | null
  stem: string
  referenceAnswer: string
}

/** 新建知识库结果：建库信息 + 解析统计（skippedCount>0 提示有题缺参考答案被跳过） */
export interface CreateKnowledgeBaseResult {
  knowledgeBase: KnowledgeBaseDto
  parsedCount: number
  skippedCount: number
}

// ===== 评估报告（GET /api/assessment/{sessionId}/report，字段对齐 AssessmentReportService.generate）=====
// 历史兼容注意：报告 JSON 是落库快照，旧版本报告可能缺新字段，渲染端全部按 optional 兜底

/** 覆盖矩阵单考点：聚合数字 + 证据链节选 */
export interface ReportCoverageItem {
  topic: string
  planned: boolean
  questionCount: number
  avgScore: number
  minScore: number
  evidences?: ReportEvidence[]
}

/** 证据链节选（完整原文在库，报告只带截断节选） */
export interface ReportEvidence {
  question: string
  answerExcerpt: string
  score: number
  feedback: string
}

/** LLM 撰写的结论三件套（生成失败时为降级占位文案） */
export interface ReportConclusion {
  overallSummary: string
  matchConclusion: string
  recommendations: string[]
}

/** 评估报告：落库快照，crossSessionWeakness 定格在生成时刻 */
export interface AssessmentReport {
  sessionId: string
  candidateId: string
  generatedAt: string
  plan?: { source: string; topics: { topic: string; priority?: string; difficulty?: string }[] }
  coverage?: ReportCoverageItem[]
  uncoveredTopics?: string[]
  outOfPlanTopics?: ReportCoverageItem[]
  crossSessionWeakness?: string
  conclusion?: ReportConclusion
}
