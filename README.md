# AI Interviewer（AI 模拟面试官）

基于 Spring AI + Vue 3 的 AI 模拟面试系统：上传/挂载知识库（RAG），针对 Java/Redis 等岗位生成面试题，逐轮追问并给出评估反馈。

## 技术栈

- **后端**：Spring Boot 3 + Spring AI（OpenAI 兼容接口，通义千问系列模型）
- **RAG**：Redis Vector Store + 文本 Embedding，知识库文档检索增强
- **MCP**：接入 Filesystem MCP Server，支持"代码评审式面试"
- **数据**：PostgreSQL（业务数据）+ Redis（向量库 / 会话缓存）
- **前端**：Vue 3 + TypeScript + Vite + Pinia，SSE 流式输出

## 项目结构

```
├── src/main/java/...      # 后端（Agent 编排 / 评估 / MCP / RAG）
├── src/main/resources/    # 配置与内置知识库文档
├── frontend/              # Vue 3 前端
├── docs/                  # 架构与开发计划文档
└── docker-compose.yml     # PostgreSQL / Redis 本地依赖
```

## 快速开始

### 1. 启动依赖

```bash
docker compose up -d
```

### 2. 配置环境变量

应用启动前必须提供大模型 API Key（阿里云 DashScope，OpenAI 兼容模式）：

```bash
# Windows PowerShell
$env:DASHSCOPE_API_KEY="sk-xxx"

# Linux / macOS
export DASHSCOPE_API_KEY=sk-xxx
```

可选：`DB_PASSWORD`（默认 `admin123`，与 docker-compose 中一致）。

### 3. 启动后端

```bash
./mvnw spring-boot:run
```

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。

## 安全说明

- `application.properties` 中不含任何真实密钥，API Key 一律通过环境变量 `DASHSCOPE_API_KEY` 注入。
- `data/`（外部知识库目录）与各类本地调试产物均已加入 `.gitignore`，不入库。
