<p align="center">
  <img src="frontend/public/logo_converted.svg" alt="EduMerge Logo" width="120" />
</p>

<h1 align="center">智融 EduMerge</h1>

<p align="center">
  <strong>AI 学习伴侣 &mdash; 将碎片化文档转化为系统性知识体系</strong>
</p>

<p align="center">
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-3.2.4-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot" /></a>
  <a href="https://nextjs.org/"><img src="https://img.shields.io/badge/Next.js-16.2.4-000000?style=flat-square&logo=nextdotjs&logoColor=white" alt="Next.js" /></a>
  <a href="https://react.dev/"><img src="https://img.shields.io/badge/React-19.2-61DAFB?style=flat-square&logo=react&logoColor=white" alt="React" /></a>
  <a href="https://milvus.io/"><img src="https://img.shields.io/badge/Milvus-2.4.4-00A1EA?style=flat-square&logo=milvus&logoColor=white" alt="Milvus" /></a>
  <a href="https://docs.langchain4j.dev/"><img src="https://img.shields.io/badge/LangChain4j-1.12-00B265?style=flat-square&logo=openai&logoColor=white" alt="LangChain4j" /></a>
  <a href="https://tailwindcss.com/"><img src="https://img.shields.io/badge/Tailwind-v4-06B6D4?style=flat-square&logo=tailwindcss&logoColor=white" alt="Tailwind" /></a>
  <a href="https://ui.shadcn.com/"><img src="https://img.shields.io/badge/shadcn/ui-components-000000?style=flat-square&logo=shadcnui&logoColor=white" alt="shadcn/ui" /></a>
  <a href="https://www.deepseek.com/"><img src="https://img.shields.io/badge/LLM-DeepSeek-536DFE?style=flat-square&logo=openai&logoColor=white" alt="DeepSeek" /></a>
  <a href="https://dashscope.aliyun.com/"><img src="https://img.shields.io/badge/Embedding-DashScope-FF6A00?style=flat-square&logo=alibabacloud&logoColor=white" alt="DashScope" /></a>
  <a href="https://www.rabbitmq.com/"><img src="https://img.shields.io/badge/RabbitMQ-async-FF6600?style=flat-square&logo=rabbitmq&logoColor=white" alt="RabbitMQ" /></a>
  <a href="#license"><img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="License" /></a>
</p>

---

## 目录

- [项目简介](#项目简介)
- [核心功能](#核心功能)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [Docker 部署](#docker-部署)
- [API 端点](#api-端点)
- [项目结构](#项目结构)

---

## 项目简介

**信息过载** 是每个深度学习者都会遭遇的困境：几十页的论文、上百页的教材、长篇的技术文档 &mdash; 读完就忘，检索低效，知识点始终碎片化。

**智融 EduMerge** 是一款集成了 RAG（检索增强生成）与多 AI 生成器协同的智能学习平台。它不只是一个"文档问答机器人"，而是一套完整的 **学习全链路系统**：

- **数据汲取** &mdash; 上传 PDF/Word/PPT/TXT，异步向量化存入 Milvus
- **知识解构** &mdash; AI 自动生成闪卡、测验、笔记、思维导图、知识图谱
- **结构化记忆** &mdash; 上下文对话、SM-2 间隔重复、错题本、FlowNote 学习日志

---

## 核心功能

### RAG 文档对话

基于检索增强生成的智能对话系统，支持 SSE 流式响应：

- **异步向量化管道**：文档上传后经 RabbitMQ 异步触发文本提取 &rarr; 递归分块 &rarr; DashScope 嵌入向量化 &rarr; Milvus 持久存储
- **上下文记忆**：基于 MySQL 持久化对话历史，跨会话续接
- **反幻觉约束**：系统提示注入"仅基于参考文献回答"约束，减少幻觉
- **OCR 回退**：扫描版 PDF 自动走 Vision LLM (Qwen-VL) 图片直传识别
- **文档级会话隔离**：每个文档独立对话线程，切换文档自动加载对应历史

### SM-2 间隔重复闪卡

AI 生成闪卡后，进入完整的间隔重复学习循环：

- **预览审核**：生成后先以网格视图预览，支持逐条编辑/删除再开始学习
- **翻转学习**：卡片正反面翻转，Space 键切换
- **SM-2 自评**：翻转后显示 4 级自评（忘了/模糊/记住/秒答），快捷键 1-4
- **到期复习**：SM-2 算法自动计算下次复习时间，`/flashcards/due` 查询到期卡片
- **生成去重**：新生成时自动排除已有卡片内容

### 测验与错题系统

AI 生成单选/多选/判断题，完成答题后进入错题循环：

- **答题记录**：每次答题保存到 `quiz_attempts` 表，`answer_details` 记录每题选择
- **错题回顾**：答题完成后自动筛选错题，逐题重做
- **全局错题本**：跨测验组聚合所有错题，支持逐题重做和标记掌握
- **薄弱度热力图**：按卡片组统计正确率，色标显示（绿 >= 80% / 黄 60-80% / 红 < 60%）

### 知识图谱

跨文档概念关系图谱：

- **AI 生成**：一次 LLM 调用处理所有文档，提取核心概念和关系
- **力导向图**：react-force-graph-2d 渲染，canvas 绘制，SSR 禁用
- **概念详情**：点击节点查看概念在各文档的来源片段

### 学习笔记 & 思维导图

- **AI 学习笔记**：从文档生成结构化学习笔记，支持版本历史
- **思维导图**：markmap 渲染 Markdown 层级树为可交互 SVG，支持暗黑模式 PNG 导出

### FlowNote 持续学习日志

记录学习过程中的洞见与想法：

- **手动添加**：随时记录学习心得
- **AI 提取**：每 5 轮对话自动触发从对话中提取知识点
- **分类筛选**：按类别组织条目
- **复习标记**：标记已复习条目

### 学习者个人中心看板

一站式学习数据面板（`/stats/learner`）：

- **今日待办**：待复习闪卡数、待重做错题数、进度环
- **学习概况**：今日学习时长、完成项数
- **周度报告**：本周 vs 上周趋势对比
- **学习节奏**：30 天 GitHub 风格热力图，周/月目标管理
- **累计成就**：10 枚成就徽章，基于数据自动解锁
- **薄弱知识点**：按文档/卡片组统计薄弱度
- **文档进度**：每个文档的学习完成度
- **今日时间线**：当日学习活动时间线

### 用户认证

- **JWT 认证**：注册/登录，JWT Token 无状态鉴权
- **安全隔离**：所有 API 端点通过 `SecurityUtils.getCurrentUserId()` 获取当前用户

### 移动端适配

- **ChatDrawer**：移动端底部半屏面板（`max-md:h-[60vh]`），桌面端右侧滑入
- **侧边栏**：移动端叠加层
- **个人中心**：桌面端右侧面板（340px），移动端跳转 `/dashboard` 全屏页面
- **全局键盘快捷键**：1-6 步骤跳转、Ctrl+/ 对话、Ctrl+Shift+D 暗黑模式

### 用户体验

- **Landing 页面**：独立着陆页，项目介绍与快速入口
- **Login / Register**：独立登录/注册页面，JWT 鉴权流程
- **OnboardingTour**：首次使用引导流程，帮助新用户快速上手
- **暗黑模式**：全局深色/浅色主题切换，所有组件自适应
- **ErrorBoundary**：全局错误边界，防止组件崩溃白屏

### 学科分类

文档上传时自动判断学科类型，生成内容时注入针对性策略：

| 类型 | 说明 |
|---|---|
| `ALGORITHM` | 算法 &mdash; 注重时间/空间复杂度分析 |
| `MATH` | 数学 &mdash; 公式推导、定理证明 |
| `PROGRAMMING` | 程序设计 &mdash; 代码示例、API 调用 |
| `SCIENCE` | 自然科学 &mdash; 实验现象、因果链 |
| `THEORY` | 计算机理论 &mdash; 形式化定义、证明 |
| `MEDICAL` | 医学 &mdash; 机制、症状、治疗 |
| `HUMANITIES` | 人文社科 &mdash; 时间线、人物、事件 |
| `GENERAL` | 通用兜底 |

---

## 架构设计

### 整体系统架构

```
┌──────────────────────────────────────────────────────────────────┐
│                       Frontend (Next.js 16)                       │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌───────────────────┐  │
│  │ ChatDrawer│ │Flashcard │ │ QuizView  │ │ KnowledgeGraph    │  │
│  │ (SSE流)  │ │ View     │ │           │ │ (force-graph-2d)  │  │
│  └────┬─────┘ └────┬─────┘ └─────┬─────┘ └────────┬──────────┘  │
│       │             │             │                 │             │
│  ┌────┴──────┐ ┌────┴──────┐ ┌────┴──────┐ ┌──────┴──────────┐  │
│  │StudyNote  │ │ FlowNote  │ │MindMap    │ │ StatsDashboard  │  │
│  │ View      │ │ View      │ │Viewer     │ │ (徽章/热力图)   │  │
│  └────┬──────┘ └────┬──────┘ └────┬──────┘ └──────┬──────────┘  │
│       │             │             │                 │             │
│  ┌────┴──────┐ ┌────┴──────┐ ┌────┴──────┐ ┌──────┴──────────┐  │
│  │Landing    │ │ Login /   │ │ Onboard   │ │ Theme / Dark    │  │
│  │ Page      │ │ Register  │ │ ingTour   │ │ Mode            │  │
│  └────┬──────┘ └────┬──────┘ └────┬──────┘ └──────┬──────────┘  │
│       └─────────────┴─────────────┴───────────────┘              │
│                         │ HTTP/REST + SSE                         │
└─────────────────────────┼────────────────────────────────────────┘
                          │
┌─────────────────────────┼────────────────────────────────────────┐
│               Backend (Spring Boot 3.2.4)            /api        │
│                         │                                         │
│  ┌──────────────────────┴──────────────────────────────────┐     │
│  │                   Controller Layer (16 个)               │     │
│  │  Document  DocumentFolder  Session  RagChat             │     │
│  │  LearningChat  Flashcard  Quiz  Deck  MindMap           │     │
│  │  StudyNote  Stats  FlowNote  KnowledgeGraph  Auth       │     │
│  │  HealthCheck                                            │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                         │
│  ┌──────────────────────┴──────────────────────────────────┐     │
│  │                    Service Layer                          │     │
│  │  AiRagService  AiFlashcardGenerator  AiQuizGenerator    │     │
│  │  AiMindMapGenerator  AiNoteGenerator  AiFlowNoteGen     │     │
│  │  AiKnowledgeGraphGenerator  SubjectClassifier           │     │
│  │  ConversationService  SessionService  StatsService      │     │
│  └──────────────────────┬──────────────────────────────────┘     │
│                         │                                         │
│  ┌──────────────────────┴──────────────────────────────────┐     │
│  │            AI & Data Infrastructure                      │     │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │     │
│  │  │ LangChain4j  │  │ Milvus SDK   │  │ MyBatis-Plus │   │     │
│  │  │ (LLM/Embed)  │  │ (向量检索)   │  │ (关系持久化) │   │     │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘   │     │
│  └─────────┼─────────────────┼─────────────────┼───────────┘     │
│            │                 │                 │                  │
│  ┌─────────┴─────────────────┴─────────────────┴───────────┐     │
│  │                RabbitMQ (异步文档向量化)                   │     │
│  └─────────────────────────────────────────────────────────┘     │
└────────────┼─────────────────┼─────────────────┼─────────────────┘
             │                 │                 │
             ▼                 ▼                 ▼
      ┌──────────┐   ┌──────────────┐   ┌───────────┐
      │ DeepSeek │   │    Milvus    │   │   MySQL   │
      │ (Chat)   │   │ (向量数据库) │   │           │
      ├──────────┤   └──────────────┘   ├───────────┤
      │ Qwen-VL  │                      │   Redis   │
      │ (Vision) │                      │           │
      └──────────┘                      └───────────┘
```

### RAG 数据处理管道

```
PDF/Word 上传
  → DocumentService 存储文件
  → EmbeddingProducer 投递任务
  → RabbitMQ EMBEDDING_QUEUE
  → DocumentListener 消费
    → PDFBox 提取文本（文本型）
    → Qwen-VL OCR（扫描型回退）
    → SubjectClassifier 学科分类
    → 递归分块 (1000/100 overlap)
    → DashScope text-embedding-v3 向量化
    → Milvus 存储 (document_id, chunk_index)
  → 更新 document.status = COMPLETED

用户提问
  → EmbeddingModel.embed()
  → MilvusEmbeddingStore.search() (相似度阈值 + Top-K)
  → 构建反幻觉系统提示
  → ChatModel.chat() (DeepSeek)
  → SSE 流式返回前端
```

### 模型配置

| 用途 | 模型 | 提供商 |
|---|---|---|
| RAG 对话 | deepseek-chat | DeepSeek |
| 内容生成（闪卡/测验/笔记） | qwen3.7-plus | DashScope (阿里云) |
| 嵌入向量化 | text-embedding-v3 | DashScope (阿里云) |
| 图像 OCR | qwen-vl-max | DashScope (阿里云) |

---

## 快速开始

### 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| Java | 17+ | 后端运行环境 |
| Maven | 3.6+ | 后端构建 |
| Node.js | 20+ | 前端开发环境 |
| MySQL | 8.0+ | 关系数据库 |
| Redis | 7.0+ | 缓存 |
| RabbitMQ | 3.x | 异步消息队列 |
| Milvus | 2.4+ | 向量数据库 |

### 1. 克隆项目

```bash
git clone https://github.com/2943331378/EduMerge.git
cd EduMerge
```

### 2. 配置环境变量

```bash
# DeepSeek API Key (Chat 模型)
export DEEPSEEK_API_KEY="sk-your-deepseek-key"

# DashScope API Key (Embedding + Vision 模型)
export AI_API_KEY="sk-your-dashscope-key"

# JWT 密钥（可选，有默认值，生产环境必须覆盖）
export JWT_SECRET="your-jwt-secret"
```

### 3. 启动中间件

```bash
# MySQL
mysqld

# Redis
redis-server

# RabbitMQ
rabbitmq-server

# Milvus (Docker)
docker run -d --name milvus-standalone \
  -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:2.4.4
```

数据库表在后端首次启动时通过 `schema.sql` 自动创建。

### 4. 启动后端

```bash
cd backend

# 构建（必须分两步 — Windows 下合并执行会导致资源打包竞态）
mvn clean
mvn package -DskipTests

# 启动
java -Dfile.encoding=UTF-8 -jar target/edumerge-backend-1.0.0.jar
```

API 服务地址：`http://localhost:8085/api`

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端地址：`http://localhost:3000`

---

## Docker 部署

项目提供完整的 Docker Compose 生产部署方案（8 个服务）：

```bash
# 1. 配置环境变量
cp .env.example .env
# 编辑 .env，填入 API Key 和数据库密码

# 2. 一键启动
docker compose up -d

# 3. 查看状态
docker compose ps
```

服务列表：

| 服务 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| mysql | mysql:8.0 | 3306 | 关系数据库，schema 自动初始化 |
| redis | redis:7-alpine | 6379 | 缓存 |
| rabbitmq | rabbitmq:3-management | 5672/15672 | 异步消息队列 + 管理面板 |
| etcd | etcd:v3.5.5 | 2379 | Milvus 元数据 |
| minio | minio | 9000/9001 | Milvus 对象存储 |
| milvus | milvusdb/milvus:v2.4.4 | 19530 | 向量数据库 |
| backend | Spring Boot | 8085 | API 服务（JVM 512m-1024m） |
| frontend | Next.js | 3000 | 前端 SSR |
| nginx | nginx:alpine | 80 | 反向代理 |

也可以使用宝塔面板部署，参考 `nginx-baota.conf` 配置。

---

## API 端点

### 文档与会话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/documents/upload` | 上传文档（PDF/Word/PPT/TXT），触发异步向量化 |
| GET | `/documents` | 查询文档列表 |
| DELETE | `/documents/{id}` | 删除文档（级联清理 Milvus 向量 + 会话） |
| GET | `/documents/{id}/chunks` | 查询文档切片 |
| GET | `/sessions` | 查询学习会话列表 |
| DELETE | `/sessions/{id}` | 删除会话 |

### RAG 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/rag/chat` | 同步 RAG 对话 |
| POST | `/chat/stream` | 流式 RAG 对话 (SSE) |
| GET | `/rag/history` | 查询对话历史 |
| GET | `/conversations` | 查询对话线程列表 |
| PUT | `/conversations/{id}` | 重命名对话线程 |
| PUT | `/rag/history/{id}/feedback` | 提交对话反馈 |

### 闪卡（SM-2 间隔重复）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/flashcards/generate` | AI 生成闪卡 |
| GET | `/flashcards` | 查询闪卡（按 docId/deckId） |
| PUT | `/flashcards/{id}` | 更新单张卡片 |
| DELETE | `/flashcards/{id}` | 删除单张卡片 |
| PUT | `/flashcards/{id}/review` | SM-2 自评（1-4） |
| GET | `/flashcards/due` | 查询到期需复习的卡片 |

### 测验与错题

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/quizzes/generate` | AI 生成测验题 |
| GET | `/quizzes` | 查询测验题 |
| PUT | `/quizzes/{id}` | 更新单道题目 |
| DELETE | `/quizzes/{id}` | 删除单道题目 |
| POST | `/quizzes/attempts` | 保存答题记录 |
| GET | `/quizzes/attempts` | 查询答题历史 |
| GET | `/quizzes/error-book` | 全局错题本 |
| GET | `/quizzes/weakness` | 按卡片组统计正确率 |

### 学习工具

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/mindmap` | 获取/生成思维导图 |
| GET | `/notes` | 获取学习笔记 |
| GET | `/notes/history` | 查询学习笔记版本历史 |
| POST | `/notes/generate` | AI 生成学习笔记 |
| GET | `/knowledge-graph` | 获取知识图谱 |
| POST | `/knowledge-graph/generate` | AI 生成知识图谱 |

### FlowNote

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/flownote` | 查询条目列表 |
| POST | `/flownote/extract` | AI 从对话中提取条目 |
| POST | `/flownote/entries` | 手动创建条目 |
| PUT | `/flownote/entries/{id}` | 更新条目 |
| DELETE | `/flownote/entries/{id}` | 删除条目 |
| PUT | `/flownote/entries/{id}/review` | 标记已复习 |
| GET | `/flownote/stats` | FlowNote 统计 |

### 统计与认证

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stats` | 数据资产看板指标 |
| GET | `/stats/learner` | 学习者个人中心看板 |
| POST | `/auth/register` | 用户注册 |
| POST | `/auth/login` | 用户登录（JWT） |
| GET | `/auth/profile` | 获取当前用户信息 |
| GET | `/health/ping` | 健康检查 |

---

## 项目结构

```
EduMerge/
├── backend/                          # Spring Boot 后端
│   └── src/main/java/com/edumerge/
│       ├── controller/               # REST 端点（16 个控制器）
│       ├── service/                  # 业务逻辑
│       ├── ai/                       # AI 生成器（7 个，继承 AiGeneratorBase）
│       ├── security/                 # JWT 认证
│       ├── mq/                       # RabbitMQ 生产者/消费者
│       ├── config/                   # Spring 配置 Bean
│       ├── store/                    # Milvus 向量存储
│       ├── entity/                   # MyBatis-Plus 实体
│       ├── mapper/                   # MyBatis-Plus Mapper
│       └── dto/                      # 数据传输对象
├── frontend/                         # Next.js 前端
│   └── src/
│       ├── app/                      # App Router 页面
│       │   ├── page.tsx              # 主页面（6 步学习路径）
│       │   ├── landing/              # 着陆页
│       │   ├── login/                # 登录页
│       │   ├── register/             # 注册页
│       │   └── dashboard/            # 个人中心全屏页
│       └── components/
│           ├── chat/                 # AI 对话（ChatDrawer, ChatRoom, MessageBubble）
│           ├── ui/                   # shadcn/ui 组件（15 个）
│           ├── FlashcardView.tsx     # 闪卡（SM-2 间隔重复）
│           ├── QuizView.tsx          # 测验 + 错题系统
│           ├── StudyNoteView.tsx     # AI 学习笔记
│           ├── StatsDashboard.tsx    # 学习者看板
│           ├── KnowledgeGraphPage.tsx # 知识图谱
│           ├── MindMapViewer.tsx     # 思维导图
│           ├── FlowNoteView.tsx      # 持续学习日志
│           ├── ErrorBookView.tsx     # 全局错题本
│           └── OnboardingTour.tsx    # 新手引导
├── docker-compose.yml                # Docker 生产部署
├── nginx.conf                        # Nginx 反向代理
├── nginx-baota.conf                  # 宝塔面板 Nginx 配置
├── scripts/                          # 工具脚本
└── docs/                             # 项目文档
```

---

## 开源许可证

本项目采用 [MIT License](LICENSE) 开源。

---

<p align="center">
  <sub>Made with ❤️ by <a href="https://github.com/2943331378">EduMerge</a> &mdash; 2026 大数据要素素质大赛参赛作品</sub>
</p>
