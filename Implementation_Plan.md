# 实现计划：考研党的 3 个核心诉求

> 基于 feedback_from_hardcore_student.md 的技术拆解
> 架构师：Agent B | 日期：2026-06-01

---

## 诉求 1：SM-2 间隔重复 + 全键盘快捷键

### 技术可行性：🟢 高（schema 已预留字段）

**后端改动：**
- `Flashcard` 实体已有 `difficulty`、`reviewCount`、`lastReviewedAt` 字段 → 无需 DDL
- 新增 `FlashcardReviewRecord` 实体 + 表：记录每次自评（quality, easeFactor, interval, nextReviewAt）
- 新增 `PUT /flashcards/{id}/review` 端点：接收自评分数(1-4)，执行 SM-2 算法更新卡片
- 新增 `GET /flashcards/due?docId=` 端点：返回到期需复习的卡片（nextReviewAt <= now）
- SM-2 核心公式：`newInterval = oldInterval * easeFactor`，`easeFactor = max(1.3, oldEF + (0.1 - (5-q)*(0.08 + (5-q)*0.02)))`

**前端改动：**
- `FlashcardView.tsx`：翻转后显示 4 个自评按钮（忘了/模糊/记住/秒答）
- 新增 `useKeyboardShortcuts` hook：Space 翻转、1-4 自评、←→ 切换、Esc 退出
- 新增"到期复习"入口：从 due 卡片队列开始学习
- 卡片翻转动画从 `duration-700` 优化为 `duration-300`

**工作量估算：** 后端 ~150 行，前端 ~200 行

---

## 诉求 2：暗黑模式沉浸感 + 全局快捷键体系

### 技术可行性：🟢 高（纯前端改动）

**改动点：**
- 新增 `useGlobalKeyboard` hook（`page.tsx` 级别）：
  - `1`-`6`：步骤跳转
  - `Ctrl+/`：开关 AI 对话
  - `Ctrl+Shift+D`：切换暗黑模式
  - `Esc`：关闭弹窗/返回
- `MindMapViewer.tsx`：PNG 导出跟随当前主题（暗黑模式导出暗色背景）
- 闪卡/测验暗色模式对比度增强（卡片背景从 `bg-card` 改为更深色值）

**工作量估算：** 前端 ~120 行

---

## 诉求 3：全局错题本 + 知识点薄弱度热力图

### 技术可行性：🟡 中（需要跨测验组聚合）

**后端改动：**
- 新增 `GET /quizzes/error-book?docId=` 端点：聚合所有 quiz_attempts 的 answer_details，筛选 correct=false 的题目
- 返回格式：按 quiz 分组，含错误次数、最近错误时间、所属 deck 信息
- 新增 `GET /quizzes/weakness?docId=` 端点：按 deck 统计正确率（利用 deck.title 作为知识点分类）

**前端改动：**
- 新增 `ErrorBookView.tsx`：全局错题列表，支持重做 + 标记已掌握
- 在 `QuizView` 的 deck 列表页添加"错题本"入口
- 正确率热力图：deck 卡片上用颜色条表示正确率（绿>80% / 黄60-80% / 红<60%）

**工作量估算：** 后端 ~100 行，前端 ~180 行

---

## 实施顺序

1. 诉求 2（快捷键 + 暗黑）→ 改动最小、体验提升最明显
2. 诉求 1（SM-2 间隔重复）→ 核心价值最大
3. 诉求 3（错题本 + 热力图）→ 依赖诉求 1 的 review 数据

---

计划已就绪，是否现在开始修改代码库落实这三个建议？
