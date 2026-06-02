# 文档大纲选择式生成 — 设计方案

> 目标：用户上传文档后，AI 自动识别文档类型并生成可编辑大纲，用户选择大纲节点后再按需生成笔记/导图/卡片/测验，提升生成质量和用户可控性。

---

## 一、现状分析

### 当前流程
```
上传文档 → 异步向量化(切块500字+50字重叠) → 全文档语义检索(top-K) → LLM 生成
```

### 核心问题
- 所有生成器（笔记/导图/卡片/测验）都对**整份文档**做语义检索，无法聚焦用户关心的章节
- 用户无法控制"只复习第3章"或"只对某几节做卡片"
- 生成内容的覆盖面依赖 top-K 参数，可能遗漏重要章节

---

## 二、整体架构变更

### 新增流程（在向量化完成后自动触发）
```
上传文档 → 异步向量化完成
                    ↓
           AI 文档类型识别 + 大纲生成（新增步骤）
                    ↓
           大纲存入 document_outlines 表
                    ↓
           前端展示可编辑大纲树
                    ↓
      用户选择节点 → 按选中章节生成笔记/导图/卡片/测验
```

### 关键设计决策
1. **大纲与切块的关联方式**：大纲每个叶子节点存储 `startChunkIndex` / `endChunkIndex`，直接映射到 `document_chunks` 表的 chunk_index 范围
2. **生成器如何使用选中章节**：新增 `selectedChunkIndices` 参数，生成器从 MySQL 直接读取指定范围的 chunks，而非从 Milvus 语义检索
3. **向后兼容**：未选择章节时仍走原有全文档语义检索逻辑

---

## 三、数据库设计

### 3.1 新增表：document_outlines

```sql
CREATE TABLE IF NOT EXISTS document_outlines (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    doc_id BIGINT NOT NULL COMMENT '关联文档ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    doc_type VARCHAR(30) NOT NULL COMMENT '文档类型: TEXTBOOK/PAPER/NOTE/SLIDE/MANUAL/OTHER',
    doc_type_label VARCHAR(50) COMMENT '文档类型中文标签',
    outline_json LONGTEXT NOT NULL COMMENT '大纲 JSON (树状结构)',
    version INT DEFAULT 1 COMMENT '版本号（用户编辑后递增）',
    deleted TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_do_doc_id (doc_id),
    INDEX idx_do_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 3.2 outline_json 结构

```json
{
  "docType": "TEXTBOOK",
  "docTypeLabel": "教材/教科书",
  "totalChunks": 120,
  "sections": [
    {
      "id": "s1",
      "title": "第一章 计算机网络概述",
      "level": 1,
      "startChunk": 0,
      "endChunk": 15,
      "children": [
        {
          "id": "s1-1",
          "title": "1.1 计算机网络的定义与分类",
          "level": 2,
          "startChunk": 0,
          "endChunk": 5,
          "children": []
        },
        {
          "id": "s1-2",
          "title": "1.2 计算机网络的发展历程",
          "level": 2,
          "startChunk": 6,
          "endChunk": 15,
          "children": []
        }
      ]
    },
    {
      "id": "s2",
      "title": "第二章 物理层",
      "level": 1,
      "startChunk": 16,
      "endChunk": 35,
      "children": [...]
    }
  ]
}
```

**字段说明**：
- `startChunk` / `endChunk`：闭区间，对应 `document_chunks.chunk_index`
- `level`：1=章, 2=节, 3=小节
- `id`：前端编辑时用于标识节点

---

## 四、后端设计

### 4.1 新增实体：DocumentOutline

```java
@TableName("document_outlines")
public class DocumentOutline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long userId;
    private String docType;
    private String docTypeLabel;
    private String outlineJson;  // JSON 字符串
    private Integer version;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 4.2 新增 AI 服务：AiOutlineGenerator

**触发时机**：DocumentListener 向量化完成后自动调用

**核心流程**：
1. 从 MySQL `document_chunks` 读取所有 chunks（按 chunk_index 排序）
2. 取前 3000 字 + 后 1000 字作为 LLM 上下文（覆盖目录和正文）
3. 调用 LLM 识别文档类型 + 生成大纲，输出 JSON
4. 解析 JSON 并校验 chunk 范围合法性
5. 持久化到 `document_outlines` 表

**Prompt 设计要点**：
```
你是一个文档结构分析专家。请分析以下文档内容，完成两个任务：

1. 判断文档类型（教材/学术论文/学习笔记/演示文稿/技术手册/其他）
2. 提取文档的章节大纲结构

输出 JSON 格式：
{
  "docType": "TEXTBOOK",
  "docTypeLabel": "教材/教科书",
  "sections": [
    {
      "id": "s1",
      "title": "章节标题",
      "level": 1,
      "startChunk": 0,
      "endChunk": 15,
      "children": [...]
    }
  ]
}

文档前部分内容：
{FRONT_CONTEXT}

文档后部分内容：
{TAIL_CONTEXT}

总切块数：{TOTAL_CHUNKS}
```

### 4.3 修改 DocumentListener

在向量化完成后调用 `AiOutlineGenerator`：

```java
// 7. 更新状态为 COMPLETED
updateDocStatus(filePath, "COMPLETED", ...);

// 8. 触发大纲生成（异步）
outlineGenerator.generateAndSave(doc.getId(), userId, segments.size());
```

### 4.4 新增 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /documents/{id}/outline | 获取文档大纲 |
| PUT | /documents/{id}/outline | 更新大纲（用户编辑后保存） |
| POST | /documents/{id}/outline/regenerate | 重新生成大纲 |

### 4.5 修改现有生成器

**改动方式**：所有生成器新增可选参数 `selectedSections`（JSON 数组，包含选中的 section id 列表）。

**有选中章节时**：
1. 从 `document_outlines` 读取大纲 JSON
2. 根据选中的 section id 收集 chunk index 范围
3. 从 MySQL `document_chunks` 直接读取指定范围的 chunks
4. 拼装上下文后调用 LLM

**无选中章节时**：走原有 Milvus 语义检索逻辑（向后兼容）

**修改的 Controller**：
- `FlashcardController.generate()` — 新增可选 `selectedSections` body 参数
- `QuizController.generate()` — 同上
- `StudyNoteController.generate()` — 同上
- `MindMapController.get()` — 同上

---

## 五、前端设计

### 5.1 新增组件：DocumentOutlineView

**位置**：学习路径的 Step 1（上传材料）和 Step 2（生成笔记）之间，作为新步骤

**UI 设计**：
```
┌─────────────────────────────────────────────┐
│  📄 文档大纲                    [重新生成]   │
│  文档类型: 教材/教科书                       │
├─────────────────────────────────────────────┤
│  ☑ 第一章 计算机网络概述              [展开] │
│    ☑ 1.1 计算机网络的定义与分类              │
│    ☐ 1.2 计算机网络的发展历程                │
│  ☑ 第二章 物理层                     [展开] │
│    ☑ 2.1 物理层的基本概念                   │
│    ☐ 2.2 数据通信的基础知识                  │
│  ☐ 第三章 数据链路层                         │
│    ☐ 3.1 ...                                │
├─────────────────────────────────────────────┤
│  已选 4/12 节  │  [编辑标题]  │ [全选/反选] │
│  ─────────────────────────────────────────  │
│  [生成笔记]  [生成导图]  [生成卡片]  [做测验]│
└─────────────────────────────────────────────┘
```

**交互逻辑**：
- 勾选章节节点 → 父节点自动半选/全选
- 点击章节标题可编辑（inline edit）
- 生成按钮将选中的 section ids 传给后端

### 5.2 修改学习路径

从 6 步扩展为 7 步：

```
1. 上传材料 → 2. 文档大纲(新) → 3. 生成笔记 → 4. 查看导图 → 5. 练卡片 → 6. 做测验 → 7. 学习日志
```

### 5.3 修改 page.tsx

- 新增 Step 2 = "文档大纲"
- 原 Step 2-6 顺延为 3-7
- `sessionCache` 新增 `outline` 字段
- Step 3-7 的生成按钮将 `selectedSections` 传给后端

### 5.4 大纲编辑功能

- **节点拖拽排序**：不实现（复杂度高，价值低）
- **节点标题编辑**：双击标题 → inline input → 失焦保存
- **节点删除**：hover 显示 × 按钮
- **手动添加节点**：底部 "+ 添加章节" 按钮
- **全选/反选**：顶部快捷操作

---

## 六、数据流示意

```
用户上传 PDF
    ↓
DocumentListener: 提取文本 → 切块 → 向量化 → 存 Milvus
    ↓
AiOutlineGenerator: 读 chunks → LLM 识别类型+大纲 → 存 document_outlines
    ↓
前端 Step 2: GET /documents/{id}/outline → 渲染大纲树
    ↓
用户勾选"第1章" + "第2章"
    ↓
前端 Step 3: POST /notes/generate { docId, selectedSections: ["s1","s2"] }
    ↓
后端: 从 outline 查 chunk 范围 → 从 MySQL 读 chunks[0..35] → 拼装上下文 → LLM
    ↓
返回仅针对第1-2章的学习笔记
```

---

## 七、工作量估算

| 模块 | 改动 | 估算 |
|------|------|------|
| schema.sql | 新增 `document_outlines` 表 | 20 行 |
| DocumentOutline 实体 + Mapper | 新建 | 60 行 |
| AiOutlineGenerator | 新建（AI 文档类型识别 + 大纲生成） | 200 行 |
| DocumentListener | 向量化完成后触发大纲生成 | 10 行 |
| DocumentController | 新增 3 个大纲 API 端点 | 80 行 |
| DocumentService | 新增大纲 CRUD 方法 | 60 行 |
| AiGeneratorBase | 新增 `loadChunksByRange()` 方法 | 30 行 |
| 4 个生成器 Controller | 新增 `selectedSections` 参数处理 | 各 20 行 |
| DocumentOutlineView 组件 | 新建（大纲树 + 编辑 + 选择） | 400 行 |
| page.tsx | 学习路径扩展 6→7 步 | 80 行 |
| api.ts | 新增大纲 API 函数 | 40 行 |
| **总计** | | **~1000 行** |

---

## 八、风险与备选方案

### 风险
1. **大纲质量依赖 LLM**：某些文档（如无章节标题的纯文本）可能生成质量差
2. **chunk 范围映射不准**：LLM 可能错误估计章节的 chunk 范围

### 缓解措施
- 大纲支持用户手动编辑修正
- 提供"重新生成大纲"功能
- chunk 范围可选：如果映射不准，回退到语义检索模式

### 备选方案：不映射 chunk 范围，用章节标题做语义检索
- 优点：不需要精确映射 chunk index
- 缺点：语义检索可能遗漏边界内容，不如精确范围可靠
- **建议**：先用 chunk 范围方案，如果效果不佳再切换到语义检索

---

## 九、需要确认的问题

1. **学习路径 6→7 步**：是否接受？还是把大纲集成到现有步骤中（如 Step 1 上传后自动显示大纲）？
2. **大纲生成时机**：向量化完成后自动生成 vs 用户手动触发？
3. **生成器的 fallback**：未选章节时是否保留原有全文档生成能力？
4. **大纲编辑的粒度**：是否需要支持手动添加/删除节点，还是只允许编辑标题和勾选？
