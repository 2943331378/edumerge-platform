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

| 功能模块 | 说明 |
|---------|------|
| 文档管理 | 上传、解析、向量化、文件夹分类、文档大纲 |
| RAG 对话 | 基于文档内容的智能问答，支持流式输出和来源追溯 |
| 学习笔记 | AI 生成 Markdown 笔记，支持编辑、版本历史、导出 |
| 思维导图 | AI 生成 markmap 格式思维导图，支持 PNG 导出 |
| 闪卡系统 | AI 生成问答卡片，SM-2 间隔重复算法，到期复习 |
| 测验系统 | AI 生成选择题/填空题，答题记录、错题本、薄弱度分析 |
| 学习日志 | 持续学习记录，AI 从对话中自动提取知识点 |
| 知识图谱 | 跨文档概念图谱，力导向可视化 |
| 学习者看板 | 今日待办、学习热力图、成就徽章、周度报告 |

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

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 3.2.4 |
| 语言 | Java | 17 |
| 构建 | Maven | 3.9+ |
| ORM | MyBatis-Plus | 3.5.7 |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis (Lettuce) | 7.x |
| 消息队列 | RabbitMQ | 3.x |
| 向量数据库 | Milvus | 2.4.4 |
| AI 框架 | LangChain4j | 1.12.1 |
| 文档解析 | Apache PDFBox / POI / Tess4J | 3.0.1 / 5.2.5 / 5.11.0 |
| 安全 | Spring Security + JWT (jjwt) | 6.2.1 / 0.12.5 |
| 可观测性 | Actuator + Prometheus + Logback | — |

### 2.2 前端

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | Next.js (App Router) | 16.2.4 |
| 语言 | TypeScript | 5.x |
| UI 框架 | React | 19.2.4 |
| 组件库 | shadcn/ui (base-nova) | 4.6.0 |
| 样式 | Tailwind CSS | v4 |
| Markdown | react-markdown + remark-gfm + remark-math + rehype-katex | — |
| 思维导图 | markmap-lib + markmap-view | — |
| 知识图谱 | react-force-graph-2d | — |
| 主题 | next-themes | 0.4.6 |
| 图标 | lucide-react | — |
| 测试 | Vitest + @testing-library/react | — |

### 2.3 AI 模型

| 用途 | 模型 | 提供商 | 端点 |
|------|------|--------|------|
| RAG 对话 | DeepSeek V4 Flash | DeepSeek | api.deepseek.com/v1 |
| 内容生成 | Qwen 3.7 Plus | DashScope | dashscope.aliyuncs.com/compatible-mode/v1 |
| 文本嵌入 | text-embedding-v4 | DashScope | dashscope.aliyuncs.com/compatible-mode/v1 |
| 视觉 OCR | Qwen VL Max | DashScope | dashscope.aliyuncs.com/compatible-mode/v1 |

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
├── EduMergeApplication.java          # 启动类
├── ai/                               # AI 能力层
│   ├── AiGeneratorBase.java          # 生成器基类（检索、上下文组装、断路器）
│   ├── AiRagService.java             # RAG 对话服务
│   ├── AiOutlineGenerator.java       # 文档大纲生成
│   ├── AiNoteGenerator.java          # 学习笔记生成
│   ├── AiMindMapGenerator.java       # 思维导图生成
│   ├── AiFlashcardGenerator.java     # 闪卡生成（并行批次）
│   ├── AiQuizGenerator.java          # 测验题生成（并行批次）
│   ├── AiFlowNoteGenerator.java      # 学习日志提取
│   ├── AiKnowledgeGraphGenerator.java # 知识图谱生成
│   ├── SubjectClassifier.java        # 学科分类器
│   ├── JpaChatMemoryStore.java       # 对话记忆持久化
│   └── CircuitBreaker.java           # 轻量断路器
├── common/
│   ├── exception/                    # 业务异常
│   ├── result/                       # Result<T> 统一响应
│   └── util/                         # 文件魔数校验
├── config/                           # Spring 配置
│   ├── SecurityConfig.java           # 安全 + CORS
│   ├── RabbitMQConfig.java           # 消息队列
│   ├── MilvusConfig.java             # 向量库
│   ├── RedisConfig.java              # 缓存
│   ├── MyBatisPlusConfig.java        # 分页插件
│   ├── AuditConfig.java              # 请求计时拦截
│   ├── DataAuditInterceptor.java     # 内容安全审计
│   ├── MdcTraceFilter.java           # 链路追踪
│   └── ThreadPoolConfig.java         # 异步线程池
├── controller/                       # REST 控制器 (16 个)
├── service/                          # 业务服务 (17 个)
├── entity/                           # 数据库实体 (20 个)
├── mapper/                           # MyBatis-Plus Mapper (20 个)
├── dto/                              # 数据传输对象
├── security/                         # JWT 认证
│   ├── JwtUtils.java                 # JWT 工具类
│   ├── JwtAuthFilter.java            # 认证过滤器
│   ├── AuthUser.java                 # 认证用户对象
│   └── SecurityUtils.java            # 安全上下文工具
├── mq/
│   ├── producer/EmbeddingProducer.java   # 向量化任务生产者
│   ├── listener/DocumentListener.java    # 向量化任务消费者
│   └── message/DocumentProcessMessage.java # 消息 DTO
└── store/
    └── MilvusEmbeddingStore.java     # LangChain4j Milvus 适配器
```

### 4.2 控制器一览

| 控制器 | 路径前缀 | 职责 |
|--------|---------|------|
| HealthCheckController | /health | 健康检查 (ping/status/info) |
| AuthController | /auth | 注册/登录/刷新/个人信息 |
| DocumentController | /documents | 文档 CRUD + 大纲 + 重试 |
| DocumentFolderController | /folders | 文件夹 CRUD + 文档移动 |
| SessionController | /sessions | 学习会话管理 |
| ConversationController | /conversations | 对话线程管理 |
| RagChatController | /rag | 同步 RAG 对话 + 历史 + 反馈 |
| LearningChatController | /chat | SSE 流式 RAG 对话 |
| StudyNoteController | /notes | 学习笔记 CRUD + AI 生成 |
| MindMapController | /mindmap | 思维导图 CRUD + AI 生成 |
| FlashcardController | /flashcards | 闪卡 CRUD + SM-2 复习 + AI 生成 |
| DeckController | /decks | 卡片组管理 |
| QuizController | /quizzes | 测验 CRUD + 答题记录 + 错题本 |
| FlowNoteController | /flownote | 学习日志 CRUD + AI 提取 |
| KnowledgeGraphController | /knowledge-graph | 知识图谱查询 + AI 生成 |
| StatsController | /stats | 数据看板 + 学习统计 + RAG 评测 |

### 4.3 服务层一览

| 服务 | 职责 |
|------|------|
| UserService | 用户注册、登录 (BCrypt) |
| DocumentService | 文档上传、CRUD、级联删除（12+ 关联表 + Milvus + 物理文件） |
| DocumentExtractionService | 文本提取 + 学科分类编排 |
| EmbeddingPipelineService | 切块、向量化、Milvus 存储、异步大纲生成 |
| SessionService | 会话 CRUD、PROCESSING 超时自动失败 |
| ConversationService | 对话线程 CRUD、交换计数（触发 FlowNote） |
| ChatHistoryService | 聊天记录 CRUD、用户反馈 |
| DocumentOutlineService | 文档大纲 CRUD、版本管理 |
| CardDeckService | 卡片组 CRUD (FLASHCARD/QUIZ/MIND_MAP/NOTE) |
| FlashcardService | 闪卡 CRUD、SM-2 算法、到期查询、重要标记 |
| QuizService | 测验 CRUD、答题记录、错题聚合、薄弱度统计 |
| StudyNoteService | 笔记 CRUD、AI 生成编排 |
| MindMapService | 思维导图 CRUD、AI 生成、getOrGenerate |
| FlowNoteService | 学习日志 CRUD、AI 提取、复习标记、分类统计 |
| KnowledgeGraphService | 知识图谱生成、概念查询、关系查询 |
| StatsService | 数据资产指标、RAG 评测存储、学习统计 |
| LearnerDashboardService | 看板聚合：今日任务、热力图、成就、时间线、周报 |

---

## 5. 前端架构

### 5.1 目录结构

```
frontend/src/
├── app/                          # Next.js App Router
│   ├── page.tsx                  # 主应用页面 (6 步学习工作流)
│   ├── layout.tsx                # 根布局
│   ├── landing/page.tsx          # 营销落地页
│   ├── login/page.tsx            # 登录页
│   ├── register/page.tsx         # 注册页 (两步)
│   ├── demo/page.tsx             # 完整演示 (无需登录)
│   ├── dashboard/page.tsx        # 移动端个人中心
│   ├── error.tsx                 # 路由级错误边界
│   ├── global-error.tsx          # 根级错误边界
│   └── globals.css               # Tailwind v4 主题变量
├── components/
│   ├── ui/                       # shadcn/ui 基础组件 (14 个)
│   ├── chat/                     # RAG 对话系统 (5 个组件)
│   ├── app-shell.tsx             # 根 Provider 组合
│   ├── app-sidebar.tsx           # 文档侧边栏 (~900 行)
│   ├── learning-path.tsx         # 步骤导航
│   ├── DocumentOutlineView.tsx   # 文档大纲编辑器
│   ├── StudyNoteView.tsx         # 笔记查看器 (SSE 流式)
│   ├── MindMapView.tsx           # 思维导图管理器
│   ├── MindMapViewer.tsx         # markmap SVG 渲染器
│   ├── FlashcardView.tsx         # 闪卡系统 (~999 行)
│   ├── QuizView.tsx              # 测验系统 (~971 行)
│   ├── FlowNoteView.tsx          # 学习日志
│   ├── KnowledgeGraphPage.tsx    # 知识图谱可视化
│   ├── StatsDashboard.tsx        # 学习者看板 (~1033 行)
│   ├── ErrorBookView.tsx         # 全局错题本
│   ├── OnboardingTour.tsx        # 新手引导
│   └── ShortcutsHelp.tsx         # 快捷键帮助
├── hooks/
│   ├── useGlobalKeyboard.ts      # 全局键盘快捷键
│   ├── useSessionState.ts        # 文档会话状态管理
│   ├── useStepNavigation.ts      # 步骤导航 (1-6)
│   └── useUploadState.ts         # 文件上传进度
├── lib/
│   ├── api.ts                    # API 抽象层 (~1118 行)
│   ├── auth-context.tsx          # 认证上下文
│   ├── utils.ts                  # cn() 工具函数
│   ├── shortcuts.ts              # 快捷键定义
│   ├── printExport.ts            # 打印导出
│   └── progressStorage.ts        # localStorage 进度持久化
└── data/
    └── demo-data.ts              # 演示数据
```

### 5.2 路由结构

| 路由 | 文件 | 需要认证 | 说明 |
|------|------|---------|------|
| `/` | page.tsx | 是 | 主学习工作区 (6 步) |
| `/landing` | landing/page.tsx | 否 | 营销落地页 |
| `/login` | login/page.tsx | 否 (已登录跳转) | 登录表单 |
| `/register` | register/page.tsx | 否 (已登录跳转) | 注册表单 |
| `/demo` | demo/page.tsx | 否 | 完整功能演示 |
| `/dashboard` | dashboard/page.tsx | 是 | 移动端个人中心 |

路由保护通过 `middleware.ts` 实现：检查 `edumerge_token` cookie，无 token 访问受保护路由时重定向到 `/landing`。

### 5.3 状态管理

项目不使用全局状态管理库，采用分层策略：

| 层级 | 机制 | 用途 |
|------|------|------|
| React Context | AuthProvider | 用户认证状态 (user, token, login, logout) |
| React Context | ThemeProvider | 主题切换 (light/dark) |
| 自定义 Hooks | useSessionState | 文档会话 + 缓存 (note, mindMap, completedSteps) |
| 自定义 Hooks | useStepNavigation | 当前步骤 (1-6) |
| 自定义 Hooks | useUploadState | 上传进度 |
| 组件本地状态 | useState/useRef | 各视图内部状态 |
| localStorage | 键值对 | 闪卡/测验进度、快捷键、引导状态、目标设定 |
| sessionStorage | 键值对 | 跨页面通信 (dashboard pending actions) |

### 5.4 API 抽象层

`lib/api.ts` (~1118 行) 封装了所有后端 API 调用：

- **`request<T>()`** — JSON 请求，自动 401 重试 (token 刷新后重发)
- **`streamRequest()`** — SSE 流式请求，解析 `data:` 行，支持 AbortSignal
- **`uploadWithToken()`** — XHR 上传，支持进度回调
- **Token 刷新去重** — 并发 401 请求共享同一个刷新 Promise，避免重复刷新

### 5.5 Provider 层级

```
<ThemeProvider>           (next-themes, class 属性, 默认 light)
  <AuthProvider>          (auth-context: user, token, loading, login, register, logout)
    {children}
    <Toaster />           (sonner, 顶部居中, 富文本颜色, 关闭按钮)
  </AuthProvider>
</ThemeProvider>
```

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

### 6.2 核心表结构

#### users — 用户表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 用户 ID |
| username | VARCHAR(50) UNIQUE | 用户名 |
| email | VARCHAR(100) UNIQUE | 邮箱 |
| password | VARCHAR(255) | BCrypt 哈希密码 |
| display_name | VARCHAR(100) | 显示名称 |
| status | VARCHAR(20) | 状态 (ACTIVE/DISABLED) |
| deleted | TINYINT DEFAULT 0 | 逻辑删除 |

默认种子数据：admin/admin123

#### documents — 文档表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 文档 ID |
| document_id | VARCHAR(128) | Milvus UUID 关联 |
| user_id | BIGINT FK | 所属用户 |
| title | VARCHAR(255) | 文档标题 |
| file_name | VARCHAR(255) | 原始文件名 |
| file_size | BIGINT | 文件大小 (字节) |
| file_type | VARCHAR(20) | 文件类型 (PDF/DOCX/PPTX/TXT) |
| file_path | VARCHAR(500) | 磁盘存储路径 |
| status | VARCHAR(20) | UPLOADING → PROCESSING → COMPLETED/FAILED |
| status_message | VARCHAR(500) | 状态消息 (失败原因) |
| chunk_count | INT | 切片数量 |
| vector_count | INT | 向量数量 |
| page_count | INT | 页数 |
| subject_type | VARCHAR(30) | 学科分类 |
| folder_id | BIGINT FK | 所属文件夹 |

#### flashcards — 闪卡表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 闪卡 ID |
| doc_id | BIGINT FK | 关联文档 |
| deck_id | BIGINT FK | 所属卡片组 |
| question | TEXT | 问题 |
| answer | TEXT | 答案 |
| explanation | TEXT | 详细解释 |
| source_segment | TEXT | 来源片段 (数据可追溯) |
| ease_factor | DECIMAL(4,2) DEFAULT 2.5 | SM-2 难度因子 |
| review_interval | INT DEFAULT 0 | 复习间隔 (天) |
| next_review_at | DATETIME | 下次复习时间 |
| is_important | TINYINT DEFAULT 0 | 重要标记 |

#### quizzes — 测验表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 题目 ID |
| doc_id | BIGINT FK | 关联文档 |
| deck_id | BIGINT FK | 所属卡片组 |
| question | TEXT | 题目 |
| options | JSON | 选项数组 |
| answer | VARCHAR(255) | 正确答案 |
| explanation | TEXT | 解析 |
| source_segment | TEXT | 来源片段 |
| quiz_type | VARCHAR(20) | SINGLE / FILL_BLANK |
| difficulty | VARCHAR(20) | 难度等级 |

#### quiz_attempts — 答题记录表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 记录 ID |
| user_id | BIGINT FK | 用户 |
| doc_id | BIGINT FK | 文档 |
| deck_id | BIGINT FK | 卡片组 |
| total_questions | INT | 总题数 |
| correct_count | INT | 正确数 |
| score_percent | DECIMAL(5,2) | 得分百分比 |
| answer_details | JSON | `[{quizId, selectedAnswer, correct}]` |

### 6.3 索引策略

- 所有外键字段均建索引
- `documents`: (user_id, status), (document_id) 联合索引
- `flashcards`: (doc_id, status), (user_id, next_review_at)
- `quizzes`: (doc_id, deck_id)
- `quiz_attempts`: (user_id, doc_id)
- `chat_history`: (session_id)
- `flow_notes`: (user_id, doc_id)

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

| 学科类型 | 说明 | 出题策略特点 |
|---------|------|-------------|
| ALGORITHM | 算法 | 强调时间复杂度、边界条件、算法对比 |
| MATH | 数学 | 要求公式推导、定理证明、数值计算 |
| PROGRAMMING | 程序设计 | 代码片段、API 调用、调试场景 |
| SCIENCE | 自然科学 | 实验设计、因果分析、定量关系 |
| THEORY | 计算机理论 | 形式化定义、证明思路、概念辨析 |
| MEDICAL | 医学 | 临床场景、鉴别诊断、用药原则 |
| HUMANITIES | 人文社科 | 多角度分析、论点论据、历史脉络 |
| GENERAL | 通用兜底 | 标准出题策略 |

### 7.3 生成器并行优化

| 生成器 | 并行策略 |
|--------|---------|
| AiFlashcardGenerator | 3 张 + 2 张两批并行生成 |
| AiQuizGenerator | 2S+1F 和 1S+1F 两批并行生成 |
| AiNoteGenerator | 单次生成 (内容较长) |
| AiMindMapGenerator | 单次生成 (结构化输出) |

### 7.4 生成去重

`FlashcardController.generate()` 和 `QuizController.generate()` 在调用 AI 前先查询已有内容的题目列表，传给 Generator 的 `existingQuestions` 参数，注入 Prompt 的 `{EXISTING_HINT}` 占位符，防止重复生成。

### 7.5 断路器 (CircuitBreaker)

轻量级实现，所有 AI Generator 共享单例：

- **CLOSED** → 正常状态
- **OPEN** → 连续 5 次失败后打开，30 秒冷却
- **HALF_OPEN** → 冷却后尝试恢复

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

### 8.2 关键参数

| 参数 | 值 | 说明 |
|------|-----|------|
| similarity_threshold | 0.35 | 向量检索最低相似度 |
| top_k | 5 | 每路检索返回的最大片段数 |
| embedding_dimension | 1024 | DashScope text-embedding-v4 维度 |
| chunk_size | 1000 | 文本切块大小 (字符) |
| chunk_overlap | 100 | 切块重叠大小 |
| multi_query_count | 2 | 变体查询数量 |
| min_relevance | 0.4 | 相关性过滤阈值 |
| memory_window | 10 | 对话记忆滑动窗口 |

### 8.3 反幻觉机制

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

| 线程池 | 核心 | 最大 | 队列 | 用途 |
|--------|------|------|------|------|
| documentTaskExecutor | 4 | 8 | 32 | 文档向量化、大纲生成 |
| asyncExecutor | 4 | 16 | 100 | SSE 流式响应 |

拒绝策略：CallerRunsPolicy（调用者线程执行）

---

## 11. 缓存策略

### 11.1 Redis 缓存配置

| 缓存名 | TTL | 用途 |
|--------|-----|------|
| dashboard | 5 分钟 | 学习者看板数据 |
| stats | 10 分钟 | 数据资产指标 |
| learningStats | 5 分钟 | 学习行为统计 |
| 默认 | 30 分钟 | 其他缓存 |

### 11.2 缓存降级

`RedisConfig` 配置了自定义错误处理器：缓存操作失败时仅记录日志，不抛异常，保证服务可用性。

### 11.3 自定义 RedisCache 工具

提供 `set/get/delete/hasKey/expire` 操作，默认 24h TTL，用于细粒度缓存控制。

---

## 12. API 接口总览

### 12.1 认证模块

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /auth/register | 用户注册 |
| POST | /auth/login | 用户登录 |
| POST | /auth/refresh | 刷新 Token |
| GET | /auth/profile | 获取当前用户信息 |

### 12.2 文档模块

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /documents/upload | 上传文档 (多文件) |
| GET | /documents | 查询文档列表 |
| GET | /documents/{id}/chunks | 查询文档切片 |
| PUT | /documents/{id} | 重命名文档 |
| DELETE | /documents/{id} | 删除文档 (级联) |
| POST | /documents/{id}/retry | 重试失败文档 |
| GET | /documents/{id}/outline | 获取文档大纲 |
| PUT | /documents/{id}/outline | 更新文档大纲 |
| POST | /documents/{id}/outline/regenerate | 重新生成大纲 |

### 12.3 文件夹模块

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /folders | 查询文件夹列表 |
| POST | /folders | 创建文件夹 |
| PUT | /folders/{id} | 更新文件夹 |
| DELETE | /folders/{id} | 删除文件夹 |
| PUT | /folders/documents/{id}/move | 移动文档到文件夹 |

### 12.4 RAG 对话模块

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /rag/chat | 同步 RAG 对话 |
| POST | /chat/stream | SSE 流式 RAG 对话 |
| GET | /rag/history | 查询对话历史 |
| PUT | /rag/history/{id}/feedback | 提交对话反馈 |

### 12.5 会话与对话线程

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /sessions | 查询会话列表 |
| GET | /sessions/{id} | 获取单个会话 |
| DELETE | /sessions/{id} | 删除会话 |
| GET | /conversations | 查询对话线程 |
| PUT | /conversations/{id} | 重命名对话线程 |
| DELETE | /conversations/{id} | 删除对话线程 |

### 12.6 学习笔记模块

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /notes | 获取学习笔记 |
| GET | /notes/history | 笔记版本历史 |
| PUT | /notes/{id} | 更新笔记 |
| POST | /notes/generate | AI 生成笔记 (同步) |
| POST | /notes/stream | AI 生成笔记 (SSE 流式) |

### 12.7 思维导图模块

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /mindmap | 获取思维导图 |
| GET | /mindmap/list | 思维导图列表 |
| GET | /mindmap/detail | 思维导图详情 |
| POST | /mindmap/generate | AI 生成 (同步) |
| POST | /mindmap/stream | AI 生成 (SSE 流式) |
| DELETE | /mindmap/{id} | 删除思维导图 |

### 12.8 闪卡模块

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /flashcards/generate | AI 生成闪卡 |
| GET | /flashcards | 查询闪卡 |
| PUT | /flashcards/{id} | 更新闪卡 |
| DELETE | /flashcards/{id} | 删除闪卡 |
| PUT | /flashcards/{id}/review | SM-2 自评 (1-4) |
| PUT | /flashcards/{id}/important | 切换重要标记 |
| GET | /flashcards/due | 查询到期复习卡片 |

### 12.9 测验模块

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /quizzes/generate | AI 生成测验题 |
| GET | /quizzes | 查询测验题 |
| PUT | /quizzes/{id} | 更新题目 |
| DELETE | /quizzes/{id} | 删除题目 |
| POST | /quizzes/attempts | 保存答题记录 |
| GET | /quizzes/attempts | 查询答题历史 |
| GET | /quizzes/error-book | 全局错题本 |
| GET | /quizzes/weakness | 薄弱度统计 |
| GET | /quizzes/mastered | 已掌握题目列表 |
| PUT | /quizzes/{id}/master | 标记已掌握 |
| DELETE | /quizzes/{id}/master | 取消掌握 |

### 12.10 学习日志模块

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /flownote | 查询日志条目 |
| POST | /flownote/extract | AI 从对话中提取 |
| POST | /flownote/entries | 手动创建条目 |
| PUT | /flownote/entries/{id} | 更新条目 |
| DELETE | /flownote/entries/{id} | 删除条目 |
| PUT | /flownote/entries/{id}/review | 标记已复习 |
| GET | /flownote/stats | 日志统计 |

### 12.11 知识图谱模块

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /knowledge-graph | 获取知识图谱 |
| POST | /knowledge-graph/generate | AI 生成知识图谱 |
| GET | /knowledge-graph/concepts/{id} | 概念详情 |
| GET | /knowledge-graph/concepts/{id}/documents | 概念来源文档 |

### 12.12 统计模块

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /stats | 数据资产指标 |
| GET | /stats/report | 自评报告 (Markdown) |
| GET | /stats/learning | 学习行为统计 |
| GET | /stats/learner | 学习者看板 |
| POST | /stats/eval | 接收 RAG 评测指标 |

### 12.13 健康检查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /health/ping | 存活检查 (返回 "PONG") |
| GET | /health/status | 详细状态 (时间戳、版本) |
| GET | /health/info | JVM 系统信息 |

---

## 13. 前端组件体系

### 13.1 核心学习视图

#### FlashcardView (~999 行)

三视图架构：
1. **卡片组列表** — 展示所有卡片组，支持创建、删除
2. **预览网格** — 生成后逐条编辑/删除，确认后开始学习
3. **卡片学习** — 翻转卡片、SM-2 自评 (1-4)、重要标记、键盘快捷键

特性：
- SM-2 间隔重复算法前端集成
- 分层答案显示（核心答案 + 可展开详细解释）
- 导出：CSV / PDF 并排 / PDF Anki 风格
- localStorage 进度持久化（7 天过期自动清理）
- 到期复习模式

#### QuizView (~971 行)

两视图架构：
1. **卡片组列表** — 展示所有测验组，支持创建、删除
2. **答题模式** — 选择题/填空题、计时器、逐题作答

特性：
- 答题完成后错题回顾模式
- 答题历史记录
- 薄弱度热力图 (绿≥80%/黄60-80%/红<60%)
- 全局错题本集成
- localStorage 进度持久化

#### StatsDashboard (~1033 行)

看板板块：
- **今日待办** — 进度环 (SVG) + 到期卡片数
- **目标管理** — 日/周/月目标 + 轻松/标准/挑战预设 (localStorage)
- **今日学习概况** — 完成量、正确率
- **周度报告** — 本周 vs 上周趋势对比
- **学习节奏** — 30 天 GitHub 风格热力图 + 连续天数
- **累计成就** — 总复习次数、答题数、文档数
- **成就徽章** — 10 枚徽章 (首次复习、复习 100/500、连续 3/7/30 天、测验高分、满分、文档探索、卡片精通)
- **薄弱知识点** — Top 错题列表
- **文档掌握度** — 每文档掌握率进度条
- **今日时间线** — 学习活动时间轴
- **情境化行动卡** — 有待复习/有错题时动态显示

#### KnowledgeGraphPage (~597 行)

- `react-force-graph-2d` 力导向图 (canvas 渲染，SSR 禁用)
- 自定义节点渲染：缩放感知标签碰撞检测
- 桌面端概念详情侧边栏 / 移动端底部抽屉
- 搜索 + 文档筛选
- "问 AI" 集成

### 13.2 RAG 对话组件

#### ChatDrawer — 对话抽屉

- 桌面端：右侧滑入面板
- 移动端：底部半屏 (`max-md:h-[85vh] rounded-t-2xl`)，支持滑动关闭
- 上下文感知标题（"关于「学习笔记」的对话"）

#### ChatRoom — 对话核心 (~683 行)

- 对话线程管理（列表、创建、重命名、删除、搜索）
- SSE 流式响应（2 分钟超时）
- 窗口化渲染（最近 100 条消息）
- 动态建议问题（基于 contextHint 生成个性化追问）
- 重试、停止、组件卸载时中止

#### MessageBubble — 消息气泡 (~365 行)

- Markdown 渲染 (remark-gfm + remark-math + rehype-highlight + rehype-katex)
- 代码块复制按钮
- 来源引用（相关性分数标签）
- 点赞/踩 + 负面反馈原因输入
- "保存为笔记"（创建 FlowNote）
- 停止标识、错误重试

### 13.3 Markdown 渲染栈

```
react-markdown
├── remark-gfm        (GitHub 风格 Markdown)
├── remark-math       (数学表达式)
├── rehype-sanitize    (HTML 消毒)
├── rehype-highlight   (代码语法高亮)
└── rehype-katex       (KaTeX 数学公式渲染)
```

### 13.4 键盘快捷键

| 范围 | 快捷键 | 功能 |
|------|--------|------|
| 全局 | 1-6 | 步骤跳转 |
| 全局 | Ctrl+/ | 切换对话面板 |
| 全局 | Ctrl+Shift+D | 切换暗黑模式 |
| 全局 | ? | 显示快捷键帮助 |
| 闪卡 | Space | 翻转卡片 |
| 闪卡 | ←→ | 切换卡片 |
| 闪卡 | 1-4 | SM-2 自评 |
| 测验 | 1-4 | 选择选项 |
| 测验 | Enter | 确认/下一题 |

冲突避免：`useGlobalKeyboard` 的 `numberKeysHandledUpTo` 参数确保闪卡自评时仅禁用数字键 1-4，5-6 仍可步骤跳转。

### 13.5 移动端适配策略

| 组件 | 桌面端 | 移动端 |
|------|--------|--------|
| 侧边栏 | 固定左侧 | 折叠侧边栏 + 长按底部 sheet |
| 对话面板 | 右侧滑入 | 底部半屏 |
| 知识图谱 | 概念详情侧边栏 | 概念详情底部抽屉 |
| 个人中心 | 右侧滑入面板 | 跳转 /dashboard 全屏页 |
| 触控目标 | — | 最小 44×44px |

---

## 14. 部署架构

### 14.1 部署拓扑 A：全 Docker (阿里云 ECS)

```
┌─────────────────────────────────────────────┐
│              Nginx (port 80)                 │
│         nginx.conf (反向代理)                 │
├─────────────────┬───────────────────────────┤
│   / (前端)       │     /api (后端)            │
│   frontend:3000  │     backend:8085          │
└─────────────────┴───────────────────────────┘

所有服务在 docker-compose.yml 中定义 (8 个容器):
├── mysql:8.0
├── redis:7-alpine
├── rabbitmq:3-management-alpine
├── etcd:v3.5.5          (Milvus 元数据)
├── minio                 (Milvus 对象存储)
├── milvus-standalone:v2.4.4
├── backend               (Spring Boot)
└── frontend              (Next.js standalone)
```

**部署命令：**
```bash
./deploy.sh
# 自动: 检查环境 → 复制 .env → 构建 → 启动 → 健康检查
```

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

| 服务 | 端口映射 |
|------|---------|
| etcd | 内部 |
| minio | 内部 |
| milvus-standalone | 19530, 9091 |
| rabbitmq | 5672, 15672 |
| frontend | 3006 |

**Nginx 配置** (nginx.baota.conf):
```nginx
location /api/ { proxy_pass http://127.0.0.1:8085/api/; }
location /     { proxy_pass http://127.0.0.1:3006; }
```

### 14.3 环境变量

#### .env.production (全 Docker)

| 变量 | 说明 | 必填 |
|------|------|------|
| DEEPSEEK_API_KEY | DeepSeek API 密钥 | 是 |
| AI_API_KEY | DashScope API 密钥 | 是 |
| MYSQL_ROOT_PASSWORD | MySQL root 密码 | 是 |
| MYSQL_DATABASE | 数据库名 | 否 (默认 edumerge) |
| REDIS_PASSWORD | Redis 密码 | 否 |
| RABBITMQ_USERNAME | RabbitMQ 用户 | 否 (默认 edumerge) |
| RABBITMQ_PASSWORD | RabbitMQ 密码 | 否 |
| NEXT_PUBLIC_API_BASE | 前端 API 基路径 | 否 (默认 /api) |
| CORS_ORIGINS | CORS 允许源 | 否 |
| JWT_SECRET | JWT 签名密钥 | 否 (有默认值) |

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

| 服务 | 内部端口 | 全 Docker 对外 | 宝塔混合对外 |
|------|---------|---------------|-------------|
| Nginx | 80 | 80 | 宝塔管理 |
| Frontend | 3000 | via Nginx | 3006 |
| Backend | 8085 | via Nginx | 8085 (宿主机) |
| MySQL | 3306 | 内部 | 宝塔管理 |
| Redis | 6379 | 内部 | 宝塔管理 |
| RabbitMQ | 5672/15672 | 内部 | 5672/15672 |
| Milvus | 19530/9091 | 内部 | 19530/9091 |

---

## 15. 监控与可观测性

### 15.1 Spring Boot Actuator

| 端点 | 说明 |
|------|------|
| /api/actuator/health | 健康检查 |
| /api/actuator/prometheus | Prometheus 指标导出 |
| /api/actuator/info | 应用信息 |
| /api/actuator/metrics | 运行时指标 |

### 15.2 日志体系

| 环境 | 日志文件 | 单文件上限 | 保留天数 | 根级别 |
|------|---------|-----------|---------|--------|
| 开发 | logs/edumerge.log | 10MB | 30 天 | DEBUG |
| 生产 | /var/log/edumerge/edumerge.log | 100MB | 90 天 | WARN (edumerge: INFO) |

特性：
- MDC 链路追踪 (`traceId` + `userId`)
- `MdcTraceFilter` 从 `X-Request-Id` 头或 UUID 生成 traceId
- JSON 结构化日志 (logstash-logback-encoder)

### 15.3 审计拦截

- **`AuditTimingInterceptor`**：记录 AI 生成端点的请求耗时
- **`DataAuditInterceptor`**：对 AI 响应进行内容安全审计（关键词过滤）

### 15.4 Docker 健康检查

所有基础设施服务均配置了 Docker 健康检查：
- MySQL: `mysqladmin ping`
- Redis: `redis-cli ping`
- RabbitMQ: `rabbitmq-diagnostics -q ping`
- Milvus: `curl /healthz`
- MinIO: `curl /minio/health/live`
- etcd: `etcdctl endpoint health`

### 15.5 RAG 评测

`scripts/evaluate_rag.py` (Python, 856 行)：
- **黄金数据集生成**：从文档切片合成 (问题, 上下文, 标准答案) 三元组
- **评测指标**：
  - Hit Rate (命中率) — DashScope 嵌入余弦相似度，阈值 0.75
  - Faithfulness (忠实度) — LLM-as-Judge，1-5 分
  - Correctness (正确性) — LLM-as-Judge，1-5 分
- **综合得分**：Hit Rate 30% + Faithfulness 35% + Correctness 35%
- **结果同步**：推送到 `/api/stats/eval` 供看板展示

---

## 16. 已知陷阱与注意事项

### 16.1 构建

| 陷阱 | 说明 | 解决方案 |
|------|------|---------|
| `mvn clean package` 合并执行 | Windows 下资源打包竞态 | 分开执行 `mvn clean` 和 `mvn package -DskipTests` |
| `mvn spring-boot:run` | Windows classpath 超 32767 字符 | 改用 `java -jar` 启动 |
| Jar 文件占用 | 后台运行后重新构建失败 | 先 `powershell -Command "Stop-Process -Name java -Force"` |
| Maven 编译 OOM | 99+ 源文件编译内存不足 | pom.xml 已配置 fork + 512m-2048m，或设 `MAVEN_OPTS=-Xmx2G` |

### 16.2 依赖

| 陷阱 | 说明 |
|------|------|
| mybatis-spring 版本 | MyBatis-Plus 3.5.7 传递引入 2.1.2，已排除并覆写为 3.0.5 以兼容 Spring 6.1 |
| LangChain4j `Document` 类 | 注意区分 `dev.langchain4j.data.document.Document` 和 `com.edumerge.entity.Document` |
| Next.js 16 | API 与 Next 14/15 有差异，写代码前查阅 `node_modules/next/dist/docs/` |

### 16.3 运行时

| 陷阱 | 说明 |
|------|------|
| 控制台编码 (Windows 中文版) | 启动前 `chcp 65001`，传 `-Dfile.encoding=UTF-8` |
| Milvus 集合管理 | 通过 REST API 删除可能不生效，用 Milvus Attu UI 操作 |
| 空检索结果 | `idScores` 为空时 `SearchResultsWrapper.getFieldData()` 抛异常，已做空检查 |
| SSE 线程安全 | `SseEmitter` 禁止多线程并发调用 `send()` |
| SSE 连接关闭 | `send("[DONE]")` 后延迟 500ms 再 `complete()`，防 `ERR_INCOMPLETE_CHUNKED_ENCODING` |
| 对话持久化 | 所有路径（onComplete/onError/空结果/异常）都保证 `saveExchange()` 被调用 |
| Stats 评测指标 | 内存 `AtomicReference`，重启丢失，需重新运行评测脚本 |
| JWT 密钥 | 默认硬编码，生产环境通过 `JWT_SECRET` 环境变量覆盖 |
| FlowNote 自动提取 | 每 5 轮对话触发，`ConcurrentHashMap` 计数，重启重置 |
| DashScope enable_search | 原生 SDK 参数，不支持 OpenAI 兼容端点 |

---

## 附录 A：Milvus 集合结构

集合 `edumerge_knowledge_chunks`：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Int64 (主键自增) | 向量 ID |
| document_id | VarChar(128) | 关联文档 UUID |
| chunk_index | Int32 | 切片序号 |
| text | VarChar(65535) | 切片文本 |
| embedding | FloatVector(1024) | 嵌入向量 |

索引：IVF_FLAT, nlist=128, COSINE 度量

## 附录 B：SM-2 间隔重复算法

实现位于 `FlashcardService.review()`：

```
EF' = max(1.3, EF + (0.1 - (5-q) * (0.08 + (5-q) * 0.02)))

其中:
  EF = 当前 ease_factor (默认 2.5)
  q = 自评质量 (1=忘了, 2=模糊, 3=记住, 4=秒答)

间隔计算:
  第 1 次: 1 天
  第 2 次: 6 天
  后续: interval = interval * EF'
```

前端翻转后显示 4 个自评按钮（忘了/模糊/记住/秒答），快捷键 1-4。`flashcard_review_logs` 表记录每次自评。

## 附录 C：文件上传支持格式

| 格式 | 解析器 | 说明 |
|------|--------|------|
| PDF | Apache PDFBox 3.0.1 | 文本 PDF 直接提取 |
| PDF (扫描版) | Tess4J (Tesseract OCR) | Vision LLM (Qwen-VL) OCR，支持图片直传 + 扫描版 PDF 自动回退 |
| DOCX | Apache POI 5.2.5 | Word 文档 |
| PPTX | Apache POI 5.2.5 | PowerPoint 演示文稿 |
| TXT | 直接读取 | 纯文本 |

上传限制：单文件 50MB，总请求 60MB。文件魔数校验防止伪装上传。

---

*文档生成时间: 2026-06-10*
*项目版本: EduMerge v1.0.0*
