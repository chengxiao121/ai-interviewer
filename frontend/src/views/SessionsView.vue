<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionsStore } from '@/stores/sessions'
import { useChatStore } from '@/stores/chat'
import MessageBubble from '@/components/MessageBubble.vue'
import type { InterviewSessionDto } from '@/types'

const sessionsStore = useSessionsStore()
const chatStore = useChatStore()
const router = useRouter()

onMounted(() => {
  sessionsStore.loadList()
})

/** 分组后的单场会话条目 */
interface GroupItem {
  sessionId: string
  /** 该候选人的第几场（1 起，按入场时间正序） */
  index: number
  timeLabel: string
  /** Redis 记忆里有对话记录 → 正常展示；否则"未开始"灰态（绑了资料但没聊起来） */
  hasRecords: boolean
  resumeFileName?: string | null
  jdFileName?: string | null
}
/** 候选人分组 */
interface CandidateGroup {
  candidateId: string
  items: GroupItem[]
}

/** createdAt ISO 串 → "MM-dd HH:mm"（解析失败回退原串截断） */
function timeLabel(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso.slice(0, 16)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 按候选人分组：
 *   绑定表是主数据源（含"未聊起来"的会话）；Redis 记忆只用来判定 hasRecords；
 *   有记忆但无绑定的孤儿会话（理论不应存在）归入"未关联"兜底组。
 * 候选人按最近一场时间倒序排列；组内按入场时间正序编号（第 1 场在最上）。
 */
const groups = computed<CandidateGroup[]>(() => {
  const memSet = new Set(sessionsStore.sessions)
  const boundIds = new Set(sessionsStore.bindings.map((b) => b.sessionId))

  const byCandidate = new Map<string, InterviewSessionDto[]>()
  for (const b of sessionsStore.bindings) {
    const list = byCandidate.get(b.candidateId) ?? []
    list.push(b)
    byCandidate.set(b.candidateId, list)
  }

  const result: CandidateGroup[] = []
  for (const [candidateId, list] of byCandidate) {
    const sorted = [...list].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    result.push({
      candidateId,
      items: sorted.map((b, i) => ({
        sessionId: b.sessionId,
        index: i + 1,
        timeLabel: timeLabel(b.createdAt),
        hasRecords: memSet.has(b.sessionId),
        resumeFileName: b.resumeFileName,
        jdFileName: b.jdFileName,
      })),
    })
  }
  result.sort((a, b) =>
    b.items[b.items.length - 1].timeLabel.localeCompare(a.items[a.items.length - 1].timeLabel),
  )

  const orphans = sessionsStore.sessions.filter((id) => !boundIds.has(id))
  if (orphans.length > 0) {
    result.push({
      candidateId: '未关联候选人',
      items: orphans.map((id) => ({
        sessionId: id,
        index: 1,
        timeLabel: '',
        hasRecords: true,
      })),
    })
  }
  return result
})

/** 删除确认用的会话标签 */
function sessionLabel(item: GroupItem): string {
  const owner = item.sessionId
  const desc = item.index ? `第 ${item.index} 场（${item.timeLabel}）` : item.timeLabel
  return `${desc} · ${owner.slice(0, 13)}`
}

async function viewHistory(id: string) {
  await sessionsStore.loadHistory(id)
}

async function switchAndChat(id: string) {
  await sessionsStore.switchTo(id)
  router.push('/chat')
}

async function clearSession(item: GroupItem) {
  if (!confirm(`确认删除${sessionLabel(item)}的会话记录？此操作不可恢复。`)) return
  await sessionsStore.clear(item.sessionId)
  // 若清空的是当前聊天会话，重置聊天页
  if (chatStore.sessionId === item.sessionId) {
    chatStore.newSession()
  }
}
</script>

<template>
  <div class="sessions-view">
    <header class="page-head">
      <div class="title-block">
        <p class="eyebrow">HISTORY · 历史会话</p>
        <h2>会话管理</h2>
      </div>
      <div class="actions">
        <button class="btn" @click="sessionsStore.loadList()" :disabled="sessionsStore.loading">
          刷新列表
        </button>
      </div>
    </header>
    <div class="page-rule"></div>

    <div class="sessions-body">
      <section class="session-list">
        <div v-if="sessionsStore.loading && groups.length === 0" class="loading">
          加载中…
        </div>
        <div v-else-if="groups.length === 0" class="empty">
          <div class="empty-seal">空</div>
          暂无会话，去「面试对话」开始第一轮吧。
        </div>
        <div v-else class="candidate-groups">
          <section v-for="g in groups" :key="g.candidateId" class="cand-group">
            <div class="group-head">
              <span class="cand-badge">候选人</span>
              <span class="cand-name" :title="g.candidateId">{{ g.candidateId }}</span>
              <span class="group-count">{{ g.items.length }} 场</span>
            </div>
            <ul class="session-cards">
              <li
                v-for="item in g.items"
                :key="item.sessionId"
                class="session-card"
                :class="{ active: sessionsStore.current === item.sessionId, pending: !item.hasRecords }"
              >
                <div class="card-top">
                  <span class="session-title">第 {{ item.index }} 场 · {{ item.timeLabel }}</span>
                  <span v-if="sessionsStore.current === item.sessionId" class="current-mark">进行中</span>
                  <span v-else-if="!item.hasRecords" class="pending-mark">未开始</span>
                </div>
                <div v-if="item.resumeFileName || item.jdFileName" class="materials-line">
                  <span v-if="item.resumeFileName" :title="item.resumeFileName">简历 {{ item.resumeFileName }}</span>
                  <span v-if="item.jdFileName" :title="item.jdFileName">JD {{ item.jdFileName }}</span>
                </div>
                <div class="sid" :title="item.sessionId">{{ item.sessionId }}</div>
                <div class="ops">
                  <button :disabled="!item.hasRecords" @click="viewHistory(item.sessionId)">查看记录</button>
                  <button class="primary" @click="switchAndChat(item.sessionId)">继续对话</button>
                  <button class="danger" @click="clearSession(item)">删除会话</button>
                </div>
              </li>
            </ul>
          </section>
        </div>
      </section>

      <section class="history-panel">
        <div class="panel-title">
          <span class="stamp-mini">录</span>
          <h3>聊天记录</h3>
        </div>
        <div v-if="sessionsStore.loading" class="loading">加载中…</div>
        <div v-else-if="sessionsStore.history.length === 0" class="empty">
          选择左侧会话，查看当时的问答记录
        </div>
        <div v-else class="history-list">
          <MessageBubble
            v-for="(msg, i) in sessionsStore.history"
            :key="i"
            :message="{ role: msg.role?.toUpperCase() === 'USER' ? 'user' : 'assistant', content: msg.content }"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.sessions-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  flex: 1;
  min-width: 0;
  position: relative;
  z-index: 1;
}
.sessions-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* 会话列表 */
.session-list {
  flex: 0 0 348px;
  border-right: 1px solid var(--rule-strong);
  overflow-y: auto;
  padding: 18px 16px;
  background: rgba(42, 36, 29, 0.025);
}
.session-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
/* 候选人分组 */
.candidate-groups {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.cand-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.group-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 0 2px;
}
.group-head .cand-badge {
  font-family: var(--mono);
  font-size: 8.5px;
  letter-spacing: 0.14em;
  color: var(--cinnabar);
  border: 1px solid var(--cinnabar);
  border-radius: 3px;
  padding: 1px 5px;
  flex-shrink: 0;
  align-self: center;
}
.group-head .cand-name {
  font-family: var(--serif);
  font-size: 15.5px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: var(--ink-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-head .group-count {
  font-family: var(--mono);
  font-size: 10.5px;
  color: var(--ink-soft);
  letter-spacing: 0.08em;
}
.session-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.session-card {
  padding: 13px 14px;
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper-raise);
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}
.session-card:hover {
  border-color: var(--rule-strong);
  transform: translateY(-1px);
}
.session-card.active {
  border-color: var(--cinnabar);
  box-shadow: -3px 0 0 var(--cinnabar);
}
.session-card.pending {
  opacity: 0.62;
  border-style: dashed;
}
.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 7px;
}
.session-title {
  font-family: var(--serif);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--ink-strong);
}
.materials-line {
  display: flex;
  gap: 12px;
  margin-bottom: 7px;
  font-size: 11.5px;
  color: var(--ink-soft);
  letter-spacing: 0.03em;
}
.materials-line span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 150px;
}
.card-top .current-mark {
  font-family: var(--serif);
  font-size: 10.5px;
  letter-spacing: 0.2em;
  color: var(--cinnabar);
  border: 1.5px solid var(--cinnabar);
  border-radius: 4px;
  padding: 2px 6px;
  flex-shrink: 0;
}
.card-top .pending-mark {
  font-family: var(--serif);
  font-size: 10.5px;
  letter-spacing: 0.2em;
  color: var(--ink-soft);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 4px;
  padding: 2px 6px;
  flex-shrink: 0;
}
.sid {
  font-family: var(--mono);
  font-size: 12.5px;
  word-break: break-all;
  margin-bottom: 10px;
  color: var(--ink-strong);
  line-height: 1.55;
}
.ops {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.ops button {
  appearance: none;
  padding: 5px 11px;
  border-radius: 4px;
  border: 1px solid var(--rule-strong);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--ink);
  letter-spacing: 0.06em;
  transition: all 0.16s ease;
}
.ops button:hover {
  border-color: var(--ink);
  background: rgba(42, 36, 29, 0.05);
}
.ops button.primary {
  background: var(--ink);
  color: var(--paper-bright);
  border-color: var(--ink);
}
.ops button.primary:hover {
  background: var(--ink-strong);
}
.ops button.danger {
  color: var(--cinnabar);
  border-color: rgba(176, 58, 43, 0.45);
}
.ops button.danger:hover {
  border-color: var(--cinnabar);
  background: var(--cinnabar-wash);
}

/* 记录面板 */
.history-panel {
  flex: 1;
  overflow-y: auto;
  padding: 20px 32px 28px;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--rule);
}
.panel-title .stamp-mini {
  width: 26px;
  height: 26px;
  font-family: var(--serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--cinnabar);
  border: 1.5px solid var(--cinnabar);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.panel-title h3 {
  margin: 0;
  font-family: var(--serif);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--ink-strong);
}
.history-list {
  display: flex;
  flex-direction: column;
  gap: 26px;
  max-width: 860px;
}
.loading,
.empty {
  color: var(--ink-soft);
  padding: 40px 24px;
  text-align: center;
  font-size: 13.5px;
  letter-spacing: 0.06em;
}
.empty-seal {
  width: 44px;
  height: 44px;
  margin: 0 auto 14px;
  font-family: var(--serif);
  font-size: 22px;
  font-weight: 600;
  color: var(--ink-soft);
  border: 1.5px dashed var(--rule-strong);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
