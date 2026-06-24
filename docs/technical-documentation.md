# EduMerge 技术文档

> 智融 EduMerge — 基于 RAG 的零幻觉知识管理与智能学习平台

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈](#2-技术栈)
3. [系统架构](#3-系统架构)
4. [后端架构](#4-后端架构)
5. [前端架构](#5-前端架构)
6. [数据库设计](#6-数据库设计)
7. [AI 能力体系](#7-ai-能力体系)
8. [RAG 检索增强生成流程](#8-rag-检索增强生成流程)
9. [安全认证体系](#9-安全认证体系)
10. [消息队列与异步处理](#10-消息队列与异步处理)
11. [缓存策略](#11-缓存策略)
12. [API 接口总览](#12-api-接口总览)
13. [前端组件体系](#13-前端组件体系)
14. [部署架构](#14-部署架构)
15. [监控与可观测性](#15-监控与可观测性)
16. [已知陷阱与注意事项](#16-已知陷阱与注意事项)

---

## 1. 项目概述

EduMerge 是一个面向学习者的智能知识管理平台，核心能力是将用户上传的文档（PDF/Word/PPT/TXT）通过 RAG（检索增强生成）技术转化为结构化学习资料，实现"零幻觉"的 AI 辅助学习。

### 1.1 核心功能

| 功能模块   | 说明                                               |
| ---------- | -------------------------------------------------- |
| 文档管理   | 上传、解析、向量化、文件夹分类、文档大纲           |
| RAG 对话   | 基于文档内容的智能问答，支持流式输出和来源追溯     |
| 学习笔记   | AI 生成 Markdown 笔记，支持编辑、版本历史、导出    |
| 思维导图   | AI 生成 markmap 格式思维导图，支持 PNG 导出        |
| 闪卡系统   | AI 生成问答卡片，SM-2 间隔重复算法，到期复习       |
| 测验系统   | AI 生成选择题/填空题，答题记录、错题本、薄弱度分析 |
| 学习日志   | 持续学习记录，AI 从对话中自动提取知识点            |
| 知识图谱   | 跨文档概念图谱，力导向可视化                       |
| 学习者看板 | 今日待办、学习热力图、成就徽章、周度报告           |

**文件支持**：PDF（文本直接提取，扫描版通过 Qwen-VL OCR 自动回退）、DOCX、PPTX、TXT。单文件 50MB，文件魔数校验防伪装上传。

### 1.2 用户工作流

```
上传文档 → 自动向量化 → 6 步学习路径:
  ① 文档大纲（AI 生成结构化目录）
  ② 学习笔记（AI 生成要点笔记）
  ③ 思维导图（AI 生成知识结构图）
  ④ 闪卡学习（AI 生成问答卡片 + SM-2 复习）
  ⑤ 知识测验（AI 生成题目 + 答题 + 错题本）
  ⑥ 学习日志（手动/AI 提取学习记录）
```

---

## 2. 技术栈

### 2.1 后端

| 类别       | 技术                            | 版本                   |
| ---------- | ------------------------------- | ---------------------- |
| 框架       | Spring Boot                     | 3.2.4                  |
| 语言       | Java                            | 17                     |
| 构建       | Maven                           | 3.9+                   |
| ORM        | MyBatis-Plus                    | 3.5.7                  |
| 数据库     | MySQL                           | 8.0                    |
| 缓存       | Redis (Lettuce)                 | 7.x                    |
| 消息队列   | RabbitMQ                        | 3.x                    |
| 向量数据库 | Milvus                          | 2.4.4                  |
| AI 框架    | LangChain4j                     | 1.12.1                 |
| 文档解析   | Apache PDFBox / POI / Tess4J    | 3.0.1 / 5.2.5 / 5.11.0 |
| 安全       | Spring Security + JWT (jjwt)    | 6.2.1 / 0.12.5         |
| 可观测性   | Actuator + Prometheus + Logback | —                      |

### 2.2 前端

| 类别     | 技术                                                     | 版本   |
| -------- | -------------------------------------------------------- | ------ |
| 框架     | Next.js (App Router)                                     | 16.2.4 |
| 语言     | TypeScript                                               | 5.x    |
| UI 框架  | React                                                    | 19.2.4 |
| 组件库   | shadcn/ui (base-nova)                                    | 4.6.0  |
| 样式     | Tailwind CSS                                             | v4     |
| Markdown | react-markdown + remark-gfm + remark-math + rehype-katex | —      |
| 思维导图 | markmap-lib + markmap-view                               | —      |
| 知识图谱 | react-force-graph-2d                                     | —      |
| 主题     | next-themes                                              | 0.4.6  |
| 图标     | lucide-react                                             | —      |
| 测试     | Vitest + @testing-library/react                          | —      |

### 2.3 AI 模型

| 用途     | 模型              | 提供商    | 端点                                      |
| -------- | ----------------- | --------- | ----------------------------------------- |
| RAG 对话 | DeepSeek V4 Flash | DeepSeek  | api.deepseek.com/v1                       |
| 内容生成 | Qwen 3.7 Plus     | DashScope | dashscope.aliyuncs.com/compatible-mode/v1 |
| 文本嵌入 | text-embedding-v4 | DashScope | dashscope.aliyuncs.com/compatible-mode/v1 |
| 视觉 OCR | Qwen VL Max       | DashScope | dashscope.aliyuncs.com/compatible-mode/v1 |

---

## 3. 系统架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                     Nginx (反向代理)                      │
│                    port 80 / 443                         │
├──────────────────────┬──────────────────────────────────┤
│     / (前端)          │        /api (后端)                │
│   Next.js SSR        │    Spring Boot REST API          │
│   port 3000          │         port 8085                │
└──────────────────────┴──────┬───────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
        ┌─────▼─────┐  ┌─────▼─────┐  ┌─────▼─────┐
        │   MySQL    │  │   Redis   │  │ RabbitMQ  │
        │   8.0      │  │   7.x    │  │   3.x    │
        └───────────┘  └───────────┘  └─────┬─────┘
                                            │
                                      ┌─────▼─────┐
                                      │ Document   │
                                      │ Listener   │
                                      └─────┬─────┘
                                            │
                              ┌─────────────┼─────────────┐
                              │             │             │
                        ┌─────▼─────┐ ┌─────▼─────┐ ┌────▼────┐
                        │  Milvus   │  │  DashScope │ │ DeepSeek│
                        │  2.4.4   │  │  Embedding │ │  Chat   │
                        │ (向量库)  │  │  + Vision  │ │         │
                        └──────────┘  └───────────┘ └─────────┘
```

### 3.2 数据流

```
用户上传文档
    │
    ▼
DocumentController.upload()
    │ 保存文件到磁盘
    │ 创建 MySQL 文档记录 (status=UPLOADING)
    │ 发送 MQ 消息 (EmbeddingProducer)
    ▼
DocumentListener (异步消费)
    │ 更新状态 PROCESSING
    │ PDFBox/POI/Tess4J 提取文本
    │ SubjectClassifier 学科分类
    │ DocumentSplitters 切块 (1000字/100字重叠)
    │ EmbeddingModel 逐块向量化 (DashScope)
    │ MilvusEmbeddingStore.addAll() 存入向量库
    │ AiOutlineGenerator 生成文档大纲
    │ 更新状态 COMPLETED
    ▼
用户进行 RAG 对话 / AI 生成学习资料
```

---

## 4. 后端架构

### 4.1 包结构

```
com.edumerge/
├── ai/            # AI 能力层 — AiGeneratorBase(基类) + 8 个 Generator + 学科分类器 + 断路器
├── controller/    # REST 控制器 (16 个)
├── service/       # 业务服务 (17 个)
├── entity/        # 数据库实体 (20 个)
├── mapper/        # MyBatis-Plus Mapper (20 个)
├── dto/           # 数据传输对象
├── security/      # JWT 认证 — JwtUtils / JwtAuthFilter / SecurityUtils
├── config/        # Spring 配置 — Security / RabbitMQ / Milvus / Redis / 审计 / 线程池
├── mq/            # 消息队列 — EmbeddingProducer(生产者) / DocumentListener(消费者)
├── store/         # MilvusEmbeddingStore — LangChain4j Milvus 适配器
└── common/        # Result<T> 统一响应 / 业务异常 / 文件魔数校验
```

### 4.2 控制器一览

| 控制器                   | 路径前缀         | 职责                            |
| ------------------------ | ---------------- | ------------------------------- |
| HealthCheckController    | /health          | 健康检查 (ping/status/info)     |
| AuthController           | /auth            | 注册/登录/刷新/个人信息         |
| DocumentController       | /documents       | 文档 CRUD + 大纲 + 重试         |
| DocumentFolderController | /folders         | 文件夹 CRUD + 文档移动          |
| SessionController        | /sessions        | 学习会话管理                    |
| ConversationController   | /conversations   | 对话线程管理                    |
| RagChatController        | /rag             | 同步 RAG 对话 + 历史 + 反馈     |
| LearningChatController   | /chat            | SSE 流式 RAG 对话               |
| StudyNoteController      | /notes           | 学习笔记 CRUD + AI 生成         |
| MindMapController        | /mindmap         | 思维导图 CRUD + AI 生成         |
| FlashcardController      | /flashcards      | 闪卡 CRUD + SM-2 复习 + AI 生成 |
| DeckController           | /decks           | 卡片组管理                      |
| QuizController           | /quizzes         | 测验 CRUD + 答题记录 + 错题本   |
| FlowNoteController       | /flownote        | 学习日志 CRUD + AI 提取         |
| KnowledgeGraphController | /knowledge-graph | 知识图谱查询 + AI 生成          |
| StatsController          | /stats           | 数据看板 + 学习统计 + RAG 评测  |

### 4.3 服务层一览

| 服务                      | 职责                                                       |
| ------------------------- | ---------------------------------------------------------- |
| UserService               | 用户注册、登录 (BCrypt)                                    |
| DocumentService           | 文档上传、CRUD、级联删除（12+ 关联表 + Milvus + 物理文件） |
| DocumentExtractionService | 文本提取 + 学科分类编排                                    |
| EmbeddingPipelineService  | 切块、向量化、Milvus 存储、异步大纲生成                    |
| SessionService            | 会话 CRUD、PROCESSING 超时自动失败                         |
| ConversationService       | 对话线程 CRUD、交换计数（触发 FlowNote）                   |
| ChatHistoryService        | 聊天记录 CRUD、用户反馈                                    |
| DocumentOutlineService    | 文档大纲 CRUD、版本管理                                    |
| CardDeckService           | 卡片组 CRUD (FLASHCARD/QUIZ/MIND_MAP/NOTE)                 |
| FlashcardService          | 闪卡 CRUD、SM-2 算法、到期查询、重要标记                   |
| QuizService               | 测验 CRUD、答题记录、错题聚合、薄弱度统计                  |
| StudyNoteService          | 笔记 CRUD、AI 生成编排                                     |
| MindMapService            | 思维导图 CRUD、AI 生成、getOrGenerate                      |
| FlowNoteService           | 学习日志 CRUD、AI 提取、复习标记、分类统计                 |
| KnowledgeGraphService     | 知识图谱生成、概念查询、关系查询                           |
| StatsService              | 数据资产指标、RAG 评测存储、学习统计                       |
| LearnerDashboardService   | 看板聚合：今日任务、热力图、成就、时间线、周报             |

---

## 5. 前端架构

### 5.1 目录结构

```
frontend/src/
├── app/                # Next.js App Router — 主页 / 登录 / 注册 / 演示 / 移动端看板
├── components/
│   ├── ui/             # shadcn/ui 基础组件 (14 个)
│   ├── chat/           # RAG 对话系统 (ChatDrawer / ChatRoom / MessageBubble 等)
│   ├── app-sidebar.tsx # 文档侧边栏
│   ├── learning-path.tsx / FlashcardView / QuizView / StatsDashboard / ...
│   └── (其余核心视图组件)
├── hooks/              # useGlobalKeyboard / useSessionState / useStepNavigation / useUploadState
├── lib/
│   ├── api.ts          # API 抽象层 (request / streamRequest / uploadWithToken)
│   ├── auth-context.tsx # 认证上下文
│   └── progressStorage.ts / printExport.ts / shortcuts.ts
└── data/               # 演示数据
```

### 5.2 路由结构

| 路由         | 文件               | 需要认证        | 说明                |
| ------------ | ------------------ | --------------- | ------------------- |
| `/`          | page.tsx           | 是              | 主学习工作区 (6 步) |
| `/landing`   | landing/page.tsx   | 否              | 营销落地页          |
| `/login`     | login/page.tsx     | 否 (已登录跳转) | 登录表单            |
| `/register`  | register/page.tsx  | 否 (已登录跳转) | 注册表单            |
| `/demo`      | demo/page.tsx      | 否              | 完整功能演示        |
| `/dashboard` | dashboard/page.tsx | 是              | 移动端个人中心      |

路由保护通过 `middleware.ts` 实现：检查 `edumerge_token` cookie，无 token 访问受保护路由时重定向到 `/landing`。

### 5.3 状态管理与 API 层

**状态分层**：AuthProvider(认证) + ThemeProvider(主题) → 自定义 Hooks(useSessionState / useStepNavigation / useUploadState) → 组件本地 useState/useRef → localStorage(进度持久化) + sessionStorage(跨页通信)。

**API 抽象层** (`lib/api.ts`)：`request<T>()`(JSON + 自动 401 重试)、`streamRequest()`(SSE + AbortSignal)、`uploadWithToken()`(XHR + 进度回调)。并发 401 共享刷新 Promise。

---

## 6. 数据库设计

### 6.1 ER 关系概览

```
users ──┬── documents ──┬── document_chunks
        │               ├── document_outlines
        │               ├── sessions ──┬── conversations ── chat_history
        │               │              └── flow_notes
        │               ├── card_decks ──┬── flashcards ── flashcard_review_logs
        │               │                ├── quizzes ── quiz_attempts
        │               │                ├── mind_maps
        │               │                └── study_notes
        │               └── document_folders (自引用)
        ├── knowledge_concepts ──┬── concept_documents
        │                       └── concept_relationships
        └── user_mastered_quizzes
```

### 6.2 核心表说明

共 20 张表，按业务域分组：

**用户与文档**：`users`(用户名/密码/状态)、`documents`(文件信息/状态/学科分类/文件夹)、`document_chunks`(切片文本/向量关联)、`document_outlines`(大纲 JSON)、`document_folders`(自引用文件夹)

**会话与对话**：`sessions`(学习会话)、`conversations`(对话线程)、`chat_history`(消息记录/反馈)

**学习卡片**：`card_decks`(卡片组，类型 FLASHCARD/QUIZ/MIND_MAP/NOTE)、`flashcards`(问答对 + SM-2 字段 `ease_factor`/`review_interval`/`next_review_at`)、`flashcard_review_logs`(自评记录)

**测验**：`quizzes`(选择题/填空题 + `options` JSON)、`quiz_attempts`(答题记录 + `answer_details` JSON)、`user_mastered_quizzes`(已掌握标记)

**其他**：`study_notes`、`mind_maps`、`flow_notes`、`knowledge_concepts` + `concept_documents` + `concept_relationships`(知识图谱三表)

**索引策略**：所有外键均建索引。关键联合索引：`documents`(user_id, status)、`flashcards`(doc_id, status)、`quiz_attempts`(user_id, doc_id)。

---

## 7. AI 能力体系

### 7.1 模型分层架构

```
┌─────────────────────────────────────────────┐
│              AiGeneratorBase                 │
│  (检索、上下文组装、JSON 提取、学科规则、断路器) │
├─────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐           │
│  │ 内容生成模型  │  │ 对话模型     │           │
│  │ Qwen 3.7+   │  │ DeepSeek V4 │           │
│  │ (笔记/卡片/  │  │ (RAG 对话)   │           │
│  │  测验/导图)   │  │             │           │
│  └─────────────┘  └─────────────┘           │
│  ┌─────────────┐  ┌─────────────┐           │
│  │ 嵌入模型     │  │ 视觉模型     │           │
│  │ text-emb-v4 │  │ Qwen VL Max │           │
│  │ (向量化)     │  │ (OCR)       │           │
│  └─────────────┘  └─────────────┘           │
└─────────────────────────────────────────────┘
```

### 7.2 学科感知分类

文档上传时自动判断学科类型，持久化到 `documents.subject_type`。各 Generator 通过 `buildSubjectRules(subjectType)` 注入针对性出题策略。

| 学科类型    | 说明       | 出题策略特点                       |
| ----------- | ---------- | ---------------------------------- |
| ALGORITHM   | 算法       | 强调时间复杂度、边界条件、算法对比 |
| MATH        | 数学       | 要求公式推导、定理证明、数值计算   |
| PROGRAMMING | 程序设计   | 代码片段、API 调用、调试场景       |
| SCIENCE     | 自然科学   | 实验设计、因果分析、定量关系       |
| THEORY      | 计算机理论 | 形式化定义、证明思路、概念辨析     |
| MEDICAL     | 医学       | 临床场景、鉴别诊断、用药原则       |
| HUMANITIES  | 人文社科   | 多角度分析、论点论据、历史脉络     |
| GENERAL     | 通用兜底   | 标准出题策略                       |

### 7.3 生成器并行优化

| 生成器               | 并行策略                    |
| -------------------- | --------------------------- |
| AiFlashcardGenerator | 3 张 + 2 张两批并行生成     |
| AiQuizGenerator      | 2S+1F 和 1S+1F 两批并行生成 |
| AiNoteGenerator      | 单次生成 (内容较长)         |
| AiMindMapGenerator   | 单次生成 (结构化输出)       |

### 7.4 生成去重

`FlashcardController.generate()` 和 `QuizController.generate()` 在调用 AI 前先查询已有内容的题目列表，传给 Generator 的 `existingQuestions` 参数，注入 Prompt 的 `{EXISTING_HINT}` 占位符，防止重复生成。

### 7.5 断路器 (CircuitBreaker)

轻量级实现，所有 AI Generator 共享单例：

- **CLOSED** → 正常状态
- **OPEN** → 连续 5 次失败后打开，30 秒冷却
- **HALF_OPEN** → 冷却后尝试恢复

### 7.6 SM-2 间隔重复算法

实现位于 `FlashcardService.review()`：

```
EF' = max(1.3, EF + (0.1 - (5-q) * (0.08 + (5-q) * 0.02)))
q: 1=忘了, 2=模糊, 3=记住, 4=秒答
间隔: 第1次 1天, 第2次 6天, 后续 interval * EF'
```

---

## 8. RAG 检索增强生成流程

### 8.1 完整流程

```
用户提问
    │
    ▼
Query Rewrite (查询改写)
    │ DeepSeek 改写用户问题为更精确的查询
    ▼
Multi-Query Retrieval (多路检索)
    │ 生成 2 个变体查询，并行向量检索
    │ 每路 Top-K=5，相似度阈值=0.35
    ▼
Relevance Filtering (相关性过滤)
    │ LLM 评估每个检索片段与问题的相关性
    │ 过滤掉 relevance < 0.4 的片段
    ▼
Context Assembly (上下文组装)
    │ 合并去重，按相关性排序
    │ 注入学科特定规则 + 反幻觉系统提示
    ▼
Response Generation (回答生成)
    │ DeepSeek 生成回答
    │ 标注来源引用 (document_id + chunk_index)
    ▼
Memory Persistence (记忆持久化)
    │ 保存到 chat_history 表
    │ 滑动窗口 10 轮对话记忆
    ▼
返回答案 + 来源引用
```

### 8.2 Milvus 集合

集合 `edumerge_knowledge_chunks`：`id`(Int64 主键) / `document_id`(VarChar 128) / `chunk_index`(Int32) / `text`(VarChar 65535) / `embedding`(FloatVector 1024)。索引：IVF_FLAT, nlist=128, COSINE 度量。

### 8.3 关键参数

| 参数                 | 值   | 说明                             |
| -------------------- | ---- | -------------------------------- |
| similarity_threshold | 0.35 | 向量检索最低相似度               |
| top_k                | 5    | 每路检索返回的最大片段数         |
| embedding_dimension  | 1024 | DashScope text-embedding-v4 维度 |
| chunk_size           | 1000 | 文本切块大小 (字符)              |
| chunk_overlap        | 100  | 切块重叠大小                     |
| multi_query_count    | 2    | 变体查询数量                     |
| min_relevance        | 0.4  | 相关性过滤阈值                   |
| memory_window        | 10   | 对话记忆滑动窗口                 |

### 8.4 反幻觉机制

- **系统提示**：明确要求"仅基于参考文献回答，不知道就说不知道"
- **来源追溯**：每个回答附带 `document_id + chunk_index` 来源引用
- **前端展示**：来源引用显示相关性分数标签（高度相关/相关/部分相关）
- **用户反馈**：支持对每条回答标记"有帮助/无帮助"并填写原因

---

## 9. 安全认证体系

### 9.1 JWT 认证流程

```
注册/登录
    │
    ▼
服务端生成 Token 对
    ├── Access Token (HS256, 2h 有效期)
    └── Refresh Token (HS256, 7d 有效期)
    │
    ▼
客户端存储
    ├── localStorage (记住我) 或 sessionStorage
    └── Cookie (middleware 路由保护)
    │
    ▼
请求携带 Authorization: Bearer <token>
    │
    ▼
JwtAuthFilter 拦截
    │ 验证 token 签名和有效期
    │ 提取 userId + username
    │ 设置 SecurityContext
    ▼
SecurityUtils.getCurrentUserId() 获取当前用户
```

### 9.2 JWT Claims 结构

```json
{
  "sub": "48",
  "username": "testuser",
  "type": "access",
  "iat": 1718000000,
  "exp": 1718007200
}
```

### 9.3 安全配置

- **CORS**：可配置的 `CORS_ORIGINS` 环境变量
- **CSRF**：禁用 (无状态 JWT)
- **Session**：STATELESS
- **公开端点**：`/auth/**`, `/health/**`, `/error`, `/actuator/**`
- **密码加密**：BCrypt
- **自定义错误**：401/403 返回 JSON 格式错误响应
- **内容安全审计**：`DataAuditInterceptor` 对 AI 生成内容进行关键词过滤（教育违规、政治敏感、有害内容、PII 检测）

### 9.4 Token 刷新机制

- 客户端收到 401 时自动调用 `/auth/refresh`
- 并发请求去重：多个 401 共享同一个刷新 Promise
- 刷新失败：清除所有存储，重定向到 `/login`

---

## 10. 消息队列与异步处理

### 10.1 RabbitMQ 拓扑

```
                     ┌─────────────────────────┐
                     │ edumerge.embedding.exchange │
                     │ (TopicExchange, durable)    │
                     └───────────┬───────────────┘
                                 │ routing key: embedding.task
                     ┌───────────▼───────────────┐
                     │ edumerge.embedding.queue     │
                     │ (durable, auto-ack)          │
                     └───────────┬───────────────┘
                                 │
                     ┌───────────▼───────────────┐
                     │ DocumentListener             │
                     │ @RabbitListener              │
                     │ concurrency=5, max=10        │
                     └───────────────────────────┘
```

### 10.2 消息流

1. **生产者** (`EmbeddingProducer`)：文档上传后在事务提交后 (`TransactionSynchronization.afterCommit()`) 发送 `DocumentProcessMessage`
2. **消费者** (`DocumentListener`)：幂等检查 → 更新状态 → 提取文本 → 切块 → 向量化 → 存储 Milvus → 生成大纲 → 更新状态
3. **重试策略**：3 次重试，指数退避 (1s-10s，乘数 2)
4. **失败处理**：更新文档状态为 FAILED，记录错误消息

### 10.3 异步线程池

| 线程池               | 核心 | 最大 | 队列 | 用途                 |
| -------------------- | ---- | ---- | ---- | -------------------- |
| documentTaskExecutor | 4    | 8    | 32   | 文档向量化、大纲生成 |
| asyncExecutor        | 4    | 16   | 100  | SSE 流式响应         |

拒绝策略：CallerRunsPolicy（调用者线程执行）

---

## 11. 缓存策略

### 11.1 Redis 缓存配置

| 缓存名        | TTL     | 用途           |
| ------------- | ------- | -------------- |
| dashboard     | 5 分钟  | 学习者看板数据 |
| stats         | 10 分钟 | 数据资产指标   |
| learningStats | 5 分钟  | 学习行为统计   |
| 默认          | 30 分钟 | 其他缓存       |

### 11.2 缓存降级

`RedisConfig` 配置了自定义错误处理器：缓存操作失败时仅记录日志，不抛异常，保证服务可用性。

### 11.3 自定义 RedisCache 工具

提供 `set/get/delete/hasKey/expire` 操作，默认 24h TTL，用于细粒度缓存控制。

---

## 12. API 接口总览

共 16 个控制器、70+ 端点，按模块汇总：

| 模块     | 路径前缀                     | 核心端点                                               |
| -------- | ---------------------------- | ------------------------------------------------------ |
| 认证     | `/auth`                      | register / login / refresh / profile                   |
| 文档     | `/documents`                 | upload / list / delete / outline / retry               |
| 文件夹   | `/folders`                   | CRUD + move                                            |
| RAG 对话 | `/rag` `/chat`               | chat (同步) / stream (SSE) / history / feedback        |
| 会话     | `/sessions` `/conversations` | list / get / delete / rename                           |
| 笔记     | `/notes`                     | generate / stream / history / update                   |
| 思维导图 | `/mindmap`                   | generate / stream / list / detail / delete             |
| 闪卡     | `/flashcards`                | generate / review(SM-2) / due / important              |
| 测验     | `/quizzes`                   | generate / attempts / error-book / weakness / mastered |
| 学习日志 | `/flownote`                  | extract / entries / review / stats                     |
| 知识图谱 | `/knowledge-graph`           | generate / concepts/{id} / documents                   |
| 统计     | `/stats`                     | learner / report / learning / eval                     |
| 健康     | `/health`                    | ping / status / info                                   |

CRUD 端点均遵循 RESTful 规范（GET list / POST create / PUT update / DELETE remove）。AI 生成端点支持同步和 SSE 流式两种模式。

---

## 13. 前端组件体系

### 13.1 核心学习视图

#### FlashcardView

三视图：卡片组列表 → 预览网格(逐条编辑/删除) → 卡片学习(翻转 + SM-2 自评 + 重要标记)。支持分层答案、CSV/PDF 导出、localStorage 进度持久化、到期复习模式。

#### QuizView

两视图：卡片组列表 → 答题模式(选择题/填空题)。支持错题回顾、答题历史、薄弱度热力图、全局错题本。

#### StatsDashboard

- **学习节奏** — 30 天 GitHub 风格热力图 + 连续天数
- **累计成就** — 总复习次数、答题数、文档数
- **成就徽章** — 10 枚徽章 (首次复习、复习 100/500、连续 3/7/30 天、测验高分、满分、文档探索、卡片精通)
- **薄弱知识点** — Top 错题列表
- **文档掌握度** — 每文档掌握率进度条
- **今日时间线** — 学习活动时间轴
- **情境化行动卡** — 有待复习/有错题时动态显示

#### KnowledgeGraphPage

`react-force-graph-2d` 力导向图（canvas 渲染，SSR 禁用）。支持搜索筛选、概念详情侧边栏/底部抽屉、"问 AI" 集成。

### 13.2 RAG 对话组件

**ChatDrawer**：桌面端右侧滑入 / 移动端底部半屏，上下文感知标题。

**ChatRoom**：对话线程管理 + SSE 流式响应 + 窗口化渲染(100 条) + 动态建议问题。

**MessageBubble**：Markdown 渲染(gfm + math + highlight + katex) + 来源引用 + 点赞/踩 + 保存为笔记。

### 13.3 Markdown 渲染栈

```

### 13.4 键盘快捷键

| 范围 | 快捷键       | 功能           |
| ---- | ------------ | -------------- |
| 全局 | 1-6          | 步骤跳转       |
| 全局 | Ctrl+/       | 切换对话面板   |
| 全局 | Ctrl+Shift+D | 切换暗黑模式   |
| 全局 | ?            | 显示快捷键帮助 |
| 闪卡 | Space        | 翻转卡片       |
| 闪卡 | ←→           | 切换卡片       |
| 闪卡 | 1-4          | SM-2 自评      |
| 测验 | 1-4          | 选择选项       |
| 测验 | Enter        | 确认/下一题    |

冲突避免：`useGlobalKeyboard` 的 `numberKeysHandledUpTo` 参数确保闪卡自评时仅禁用数字键 1-4，5-6 仍可步骤跳转。

### 13.5 移动端适配策略

| 组件     | 桌面端         | 移动端                      |
| -------- | -------------- | --------------------------- |
| 侧边栏   | 固定左侧       | 折叠侧边栏 + 长按底部 sheet |
| 对话面板 | 右侧滑入       | 底部半屏                    |
| 知识图谱 | 概念详情侧边栏 | 概念详情底部抽屉            |
| 个人中心 | 右侧滑入面板   | 跳转 /dashboard 全屏页      |
| 触控目标 | —              | 最小 44×44px                |

---

## 14. 部署架构

### 14.1 部署拓扑 A：全 Docker (阿里云 ECS)

```

┌─────────────────────────────────────────────┐
│ Nginx (port 80) │
│ nginx.conf (反向代理) │
├─────────────────┬───────────────────────────┤
│ / (前端) │ /api (后端) │
│ frontend:3000 │ backend:8085 │
└─────────────────┴───────────────────────────┘

所有服务在 docker-compose.yml 中定义 (8 个容器):
├── mysql:8.0
├── redis:7-alpine
├── rabbitmq:3-management-alpine
├── etcd:v3.5.5 (Milvus 元数据)
├── minio (Milvus 对象存储)
├── milvus-standalone:v2.4.4
├── backend (Spring Boot)
└── frontend (Next.js standalone)

````

**部署命令：**

```bash
./deploy.sh
# 自动: 检查环境 → 复制 .env → 构建 → 启动 → 健康检查
````

**更新命令：**

```bash
git pull
docker compose down
docker compose build --parallel
docker compose up -d
```

### 14.2 部署拓扑 B：宝塔面板混合部署

```
宝塔面板管理: MySQL + Redis + Nginx (宿主机)
Docker 容器:  etcd + minio + milvus + rabbitmq + frontend
宿主机进程:   backend JAR (java -jar)
```

**Docker 服务** (docker-compose.baota.yml):

| 服务              | 端口映射    |
| ----------------- | ----------- |
| etcd              | 内部        |
| minio             | 内部        |
| milvus-standalone | 19530, 9091 |
| rabbitmq          | 5672, 15672 |
| frontend          | 3006        |

**Nginx 配置** (nginx.baota.conf):

```nginx
location /api/ { proxy_pass http://127.0.0.1:8085/api/; }
location /     { proxy_pass http://127.0.0.1:3006; }
```

### 14.3 环境变量

#### .env.production (全 Docker)

| 变量                 | 说明               | 必填               |
| -------------------- | ------------------ | ------------------ |
| DEEPSEEK_API_KEY     | DeepSeek API 密钥  | 是                 |
| AI_API_KEY           | DashScope API 密钥 | 是                 |
| MYSQL_ROOT_PASSWORD  | MySQL root 密码    | 是                 |
| MYSQL_DATABASE       | 数据库名           | 否 (默认 edumerge) |
| REDIS_PASSWORD       | Redis 密码         | 否                 |
| RABBITMQ_USERNAME    | RabbitMQ 用户      | 否 (默认 edumerge) |
| RABBITMQ_PASSWORD    | RabbitMQ 密码      | 否                 |
| NEXT_PUBLIC_API_BASE | 前端 API 基路径    | 否 (默认 /api)     |
| CORS_ORIGINS         | CORS 允许源        | 否                 |
| JWT_SECRET           | JWT 签名密钥       | 否 (有默认值)      |

### 14.4 Docker 镜像构建

#### 后端 (Dockerfile.backend)

```
Stage 1 (builder): maven:3.9-eclipse-temurin-17
  → mvn dependency:go-offline (层缓存)
  → mvn clean package -DskipTests

Stage 2 (runner): eclipse-temurin:17-jre-alpine
  → 安装 Tesseract OCR (chi_sim + eng)
  → 复制 JAR
  → JVM: -Xms512m -Xmx1024m -XX:+UseG1GC
```

#### 前端 (Dockerfile.frontend)

```
Stage 1 (builder): node:22-slim
  → npm ci (PUPPETEER_SKIP_DOWNLOAD=true)
  → npm run build (NEXT_PUBLIC_API_BASE=/api)
  → NODE_OPTIONS=--max-old-space-size=1536

Stage 2 (runner): node:22-slim
  → 复制 standalone 输出
  → node server.js (port 3000)
```

### 14.5 端口参考

| 服务     | 内部端口   | 全 Docker 对外 | 宝塔混合对外  |
| -------- | ---------- | -------------- | ------------- |
| Nginx    | 80         | 80             | 宝塔管理      |
| Frontend | 3000       | via Nginx      | 3006          |
| Backend  | 8085       | via Nginx      | 8085 (宿主机) |
| MySQL    | 3306       | 内部           | 宝塔管理      |
| Redis    | 6379       | 内部           | 宝塔管理      |
| RabbitMQ | 5672/15672 | 内部           | 5672/15672    |
| Milvus   | 19530/9091 | 内部           | 19530/9091    |

---

## 15. 监控与可观测性

**Actuator 端点**：`/health`（健康检查）、`/prometheus`（指标导出）、`/metrics`（运行时指标）

**日志**：MDC 链路追踪（`traceId` + `userId`），JSON 结构化输出（logstash-logback-encoder）。开发环境 DEBUG 级别 10MB/30 天，生产环境 WARN 级别 100MB/90 天。

**审计拦截**：`AuditTimingInterceptor` 记录 AI 端点耗时；`DataAuditInterceptor` 对 AI 响应做内容安全审计（关键词过滤）。

**RAG 评测**：`scripts/evaluate_rag.py` 从文档切片生成黄金数据集，计算 Hit Rate（30%）+ Faithfulness（35%）+ Correctness（35%），结果推送到 `/stats/eval`。

**Docker 健康检查**：MySQL / Redis / RabbitMQ / Milvus / MinIO / etcd 均配置了容器级健康检查。

---

## 16. 已知陷阱与注意事项

### 16.1 构建

| 陷阱                         | 说明                            | 解决方案                                                   |
| ---------------------------- | ------------------------------- | ---------------------------------------------------------- |
| `mvn clean package` 合并执行 | Windows 下资源打包竞态          | 分开执行 `mvn clean` 和 `mvn package -DskipTests`          |
| `mvn spring-boot:run`        | Windows classpath 超 32767 字符 | 改用 `java -jar` 启动                                      |
| Jar 文件占用                 | 后台运行后重新构建失败          | 先 `powershell -Command "Stop-Process -Name java -Force"`  |
| Maven 编译 OOM               | 99+ 源文件编译内存不足          | pom.xml 已配置 fork + 512m-2048m，或设 `MAVEN_OPTS=-Xmx2G` |

### 16.2 依赖

| 陷阱                      | 说明                                                                                |
| ------------------------- | ----------------------------------------------------------------------------------- |
| mybatis-spring 版本       | MyBatis-Plus 3.5.7 传递引入 2.1.2，已排除并覆写为 3.0.5 以兼容 Spring 6.1           |
| LangChain4j `Document` 类 | 注意区分 `dev.langchain4j.data.document.Document` 和 `com.edumerge.entity.Document` |
| Next.js 16                | API 与 Next 14/15 有差异，写代码前查阅 `node_modules/next/dist/docs/`               |

### 16.3 运行时

| 陷阱                        | 说明                                                                                |
| --------------------------- | ----------------------------------------------------------------------------------- |
| 控制台编码 (Windows 中文版) | 启动前 `chcp 65001`，传 `-Dfile.encoding=UTF-8`                                     |
| Milvus 集合管理             | 通过 REST API 删除可能不生效，用 Milvus Attu UI 操作                                |
| 空检索结果                  | `idScores` 为空时 `SearchResultsWrapper.getFieldData()` 抛异常，已做空检查          |
| SSE 线程安全                | `SseEmitter` 禁止多线程并发调用 `send()`                                            |
| SSE 连接关闭                | `send("[DONE]")` 后延迟 500ms 再 `complete()`，防 `ERR_INCOMPLETE_CHUNKED_ENCODING` |
| 对话持久化                  | 所有路径（onComplete/onError/空结果/异常）都保证 `saveExchange()` 被调用            |
| Stats 评测指标              | 内存 `AtomicReference`，重启丢失，需重新运行评测脚本                                |
| JWT 密钥                    | 默认硬编码，生产环境通过 `JWT_SECRET` 环境变量覆盖                                  |
| FlowNote 自动提取           | 每 5 轮对话触发，`ConcurrentHashMap` 计数，重启重置                                 |
| DashScope enable_search     | 原生 SDK 参数，不支持 OpenAI 兼容端点                                               |

---
