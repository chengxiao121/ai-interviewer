import { createRouter, createWebHashHistory } from 'vue-router'

// 路由表：聊天 / 会话管理 / 知识库
// 用 hash 模式（/#/chat）：生产态由 Spring Boot 静态托管，无需服务端 SPA 回退，
// 浏览器不会把 /chat 发给服务器，只请求 / 和静态资源，最稳健。
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: () => import('@/views/ChatView.vue') },
    { path: '/sessions', name: 'sessions', component: () => import('@/views/SessionsView.vue') },
    { path: '/knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue') },
    // 报告页：/report/:sessionId?candidateId=xxx（从会话卡片 / 聊天页头部进入，不在侧栏导航中）
    { path: '/report/:sessionId', name: 'report', component: () => import('@/views/ReportView.vue') },
  ],
})

export default router
