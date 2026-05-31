# CLAUDE.md

本文件为 Claude Code 提供本仓库的代码指引。

## 构建与运行

### 后端 (Spring Boot 3.2.4 + Java 17)

```bash
# 构建（必须分开执行 clean 和 package — Windows 下合并执行会导致资源打包竞态）
cd backend
mvn clean
mvn package -DskipTests

# 运行（不要用 mvn spring-boot:run — Windows 下会触发 32767 字符的 classpath 上限）
java -Dfile.encoding=UTF-8 -jar target/edumerge-backend-1.0.0.jar

# 强制结束残留 Java 进程 (Windows)
powershell -Command "Stop-Process -Name java -Force"
```

### 前端 (Next.js 16.2.4 + shadcn/ui + Tailwind v4)

```bash
cd frontend
npm run dev       # 开发服务器 localhost:3000
npm run build     # 生产构建
```

核心组件：
- `page.tsx` — 主页面，含 6 步学习路径、按会话缓存状态、移动端适配
- `app-sidebar.tsx` — 侧边栏，含文档列表、搜索过滤、上传区域、移动端叠加层
- `chat/ChatDrawer.tsx` — AI 对话抽屉（移动端全宽）
- `StatsDashboard.tsx` — 数据资产看板（文档数、RAG 评测指标、合规治理）
- `FlashcardView.tsx` — 闪卡组，含 AI 生成 + 取消 + AbortController
- `QuizView.tsx` — 测验组，含 AI 生成 + 取消 + 答题历史持久化
- `StudyNoteView.tsx` — AI 生成学习笔记，含 AbortController 取消
- `FlowNoteView.tsx` — 持续学习日志，含分类筛选/AI提取/手动添加/复习标记/导出
- `MindMapViewer.tsx` — markmap 思维导图渲染器（手动触发生成）
- `learning-path.tsx` — 6 步进度导航（响应式，移动端紧凑布局）

### 关键设计模式

- **按会话缓存状态**：`page.tsx` 使用 `sessionCache`（Map<sessionId, {note, mindMap, completedSteps}>）在切换文档时保留 AI 生成内容，不再丢失状态。
- **AbortController 取消生成**：FlashcardView、QuizView 和 StudyNoteView 使用 `AbortController` + `abortRef` 取消进行中的 AI 生成请求。API 函数接受可选的 `signal?: AbortSignal` 参数。
- **FlowNote 自动提取**：`AiRagService.saveExchange()` 每 5 轮对话自动触发 `FlowNoteService.extractFromChat()`，将对话内容提炼为结构化学习日志（KEY_POINT/QUESTION/EXAMPLE/REVIEW）。
- **移动端适配**：侧边栏在移动端以叠加层展示（`max-md:fixed`），ChatDrawer 在移动端全宽（`md:max-w-lg`），LearningPath 在移动端使用更小图标。
- **测验答题持久化**：`QuizView` 用 `answers[]` 状态追踪答案，完成后通过 `POST /api/quizzes/attempts` 保存到后端，在 deck 列表视图展示历史得分。
- **JWT 认证**：`SecurityUtils.getCurrentUserId()` 从 SecurityContext 提取当前用户 ID，所有 Controller 不再硬编码 userId。

## 架构

### 后端分层

```
controller/          → REST 端点（Result<T> 统一响应）— 14 个控制器
  service/           → 业务逻辑（RagChatService: RAG 流程, FlowNoteService: 持续学习日志）
  ai/                → AI 生成器（AiRagService, AiFlashcardGenerator, AiQuizGenerator,
                       AiMindMapGenerator, AiNoteGenerator, AiFlowNoteGenerator — 均继承 AiGeneratorBase）
  security/          → JWT 认证（JwtUtils, JwtAuthFilter, SecurityUtils, AuthUser）
  mq/
    producer/        → RabbitMQ 消息发送（EmbeddingProducer）
    listener/        → @RabbitListener 消费者（DocumentListener: 异步 PDF 向量化）
    message/         → RabbitMQ 传输的 DTO
  config/            → Spring @Configuration Bean（Milvus, RabbitMQ, Redis, CORS, MyBatis-Plus, Security）
  store/             → MilvusEmbeddingStore — 基于 Milvus SDK 2.4.4 实现的
                       LangChain4j EmbeddingStore<TextSegment>。支持 addAll, search, deleteByDocumentId
  entity/            → MyBatis-Plus @TableName 实体（User, Document, DocumentChunk, Session,
                       Conversation, ChatHistory, CardDeck, Flashcard, Quiz, QuizAttempt,
                       MindMap, StudyNote, FlowNote）
  mapper/            → MyBatis-Plus BaseMapper 接口（无需 XML）
  dto/               → StatsResponse, FlowNoteExtractRequest（数据资产指标、RAG 评测指标、合规治理）
  common/result/     → Result<T> 统一 API 响应包装（code=0 成功，非 0 失败）
```

### API 端点总览

| 方法 | 路径 | 说明 |
|--------|------|-------------|
| POST | /documents/upload | 上传文档（PDF/Word/PPT/TXT），触发异步向量化 |
| GET | /documents | 查询文档列表（最近 50 条） |
| DELETE | /documents/{id} | 删除文档 + 文件 + Milvus 向量 + 关联会话 |
| GET | /documents/{id}/chunks | 查询文档切片（评测黄金数据集） |
| GET | /sessions | 查询学习会话列表（含文档状态） |
| DELETE | /sessions/{id} | 删除会话 |
| POST | /rag/chat | 同步 RAG 对话（含上下文记忆） |
| POST | /chat/stream | 流式 RAG 对话 (SSE) |
| GET | /rag/history | 查询会话的对话历史 |
| GET | /conversations | 查询对话线程列表 |
| DELETE | /conversations/{id} | 删除对话线程 |
| GET | /flashcards | 查询闪卡（按 docId/sessionId/deckId） |
| POST | /flashcards/generate | AI 生成闪卡（支持 AbortSignal 取消） |
| GET | /quizzes | 查询测验题（按 docId/sessionId/deckId） |
| POST | /quizzes/generate | AI 生成测验题（支持 AbortSignal 取消） |
| POST | /quizzes/attempts | 保存测验答题记录 |
| GET | /quizzes/attempts?docId= | 查询某文档的答题历史 |
| GET | /mindmap?docId= | 获取或生成思维导图（带缓存） |
| GET | /notes?docId= | 获取学习笔记（最新版本） |
| GET | /notes/history?docId= | 查询学习笔记版本历史 |
| POST | /notes/generate | AI 生成学习笔记 |
| GET | /decks | 查询卡片组（按 docId + type） |
| DELETE | /decks/{id} | 删除卡片组 |
| GET | /stats | 全维度数据资产看板指标 |
| GET | /stats/report | 数据素质自评报告 (Markdown) |
| POST | /stats/eval | 接收 evaluate_rag.py 推送的 RAG 评测指标 |
| POST | /auth/register | 用户注册 |
| POST | /auth/login | 用户登录，返回 JWT |
| GET | /auth/profile | 获取当前用户信息 |
| GET | /flownote?docId= | 查询 FlowNote 条目列表（支持 category 筛选） |
| POST | /flownote/extract | AI 从对话中提取 FlowNote 条目 |
| POST | /flownote/entries | 手动创建 FlowNote 条目 |
| PUT | /flownote/entries/{id} | 更新 FlowNote 条目 |
| DELETE | /flownote/entries/{id} | 删除 FlowNote 条目 |
| PUT | /flownote/entries/{id}/review | 标记条目已复习 |
| GET | /flownote/stats?docId= | FlowNote 统计（总数/复习率/分类分布） |
| GET | /health/ping | 健康检查 |

### RAG 流程 (RagChatService.chat)

```
用户提问 → EmbeddingModel.embed()（DashScope text-embedding-v3，OpenAI 兼容 API）
         → MilvusEmbeddingStore.search()（相似度阈值 + Top-K）
         → 构建反幻觉系统提示（"仅基于参考文献回答"）
         → ChatLanguageModel.generate()（DeepSeek deepseek-chat，OpenAI 兼容 API）
         → 返回答案 + 来源引用
```

### 异步文档向量化 (RabbitMQ)

```
POST /api/documents/upload → 保存文件 → EmbeddingProducer.sendEmbeddingTask()
  → RabbitMQ EMBEDDING_QUEUE → DocumentListener.handleEmbeddingTask()
      → Apache PDFBox 提取文本
      → DocumentSplitters.recursive(500, 50) 切块
      → EmbeddingModel 逐块向量化
      → MilvusEmbeddingStore.addAll() 存入 Milvus（metadata: document_id, chunk_index）
```

### Milvus 集合结构

集合 `edumerge_knowledge_chunks`：`id` (Int64 主键自增)、`document_id` (VarChar 128)、`chunk_index` (Int32)、`text` (VarChar 65535)、`embedding` (FloatVector 1024)

### 模型配置

`MilvusVectorStoreConfig` 中：Chat → DeepSeek（`deepseek-chat`，api.deepseek.com/v1）。Embedding → DashScope（`text-embedding-v3`，dashscope.aliyuncs.com/compatible-mode/v1）。两者均使用 `OpenAiChatModel`/`OpenAiEmbeddingModel` builder 配合自定义 base URL。Embedding 的 API Key 和 Base URL 独立配置（DeepSeek 不支持 embedding）。

## 已知陷阱

### 构建

- **`mvn clean package` 合并执行**：Windows 下可能导致资源未打包到 jar 中。务必分开执行 `mvn clean` 和 `mvn package -DskipTests`。
- **`mvn spring-boot:run`**：Windows 下报 `CreateProcess error=206`，classpath 字符串超出 32767 字符限制。改用 `java -jar` 启动。
- **Jar 文件占用**：`java -jar &` 后台运行后，重新构建前需先结束进程：`powershell -Command "Stop-Process -Name java -Force"`。Windows 中文版上的 `taskkill /F /IM java.exe` 经常静默失败。

### 依赖

- **mybatis-spring**：必须 ≥ 3.0.4 以兼容 Spring 6.1。MyBatis-Plus 3.5.7 传递引入 2.1.2，已排除并覆写为 3.0.5（见 pom.xml）。
- **LangChain4j `Document` 类**：注意区分 `dev.langchain4j.data.document.Document` 和项目自己的 `com.edumerge.entity.Document`，不要导错。
- **Next.js 16**：API 与 Next 14/15 训练数据有差异。写代码前先查阅 `node_modules/next/dist/docs/`。

### 运行时

- **控制台编码 (Windows 中文版)**：启动前先执行 `chcp 65001`，并传 `-Dfile.encoding=UTF-8`。logback-spring.xml 已配置 `<charset>UTF-8</charset>`。
- **Milvus 集合**：通过 `ensureCollection()` 自动创建索引（IVF_FLAT, nlist=128）并加载到内存。通过 REST API 删除可能不生效 — 用 Milvus Attu UI 操作。
- **空检索结果**：`idScores` 为空时 `SearchResultsWrapper.getFieldData()` 会抛 `ParamException`。`MilvusEmbeddingStore.search()` 已做空检查防护。
- **文档删除级联**：`DocumentService.delete()` 执行顺序：磁盘文件 → Milvus 向量（`deleteByDocumentId`）→ MySQL 切片 → 会话 → 文档（软删除）。Milvus 删除失败仅记录日志，不阻塞 MySQL 清理。
- **测验答题表**：新增 `quiz_attempts` 表存储每次答题记录。`answer_details` 为 JSON 列，格式 `[{quizId, selectedAnswer, correct}]`。由 schema.sql 定义，调用 QuizController `/attempts` 端点前需确保表已存在。
- **Stats 评测指标**：`StatsService.evalMetricsRef` 为内存中的 `AtomicReference`，重启即丢失。每次部署后需重新运行 `evaluate_rag.py` 推送数据。
- **JWT 密钥**：默认密钥硬编码在 `application.yml` 中（`edumerge-jwt-secret-key-2026-for-hs256`），生产环境应通过 `JWT_SECRET` 环境变量覆盖。
- **FlowNote 自动提取**：每 5 轮对话触发一次，通过 `ConcurrentHashMap` 计数（服务重启后重置）。提取依赖 `documentId` (Milvus UUID) 精确关联文档，如果对话时未传 `documentId` 则跳过提取。
