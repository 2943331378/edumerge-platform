# Code Review 修复清单

## 🔴 致命

### 1. SecurityContext 泄漏到 LLM 线程池 ✅
- **文件**: `AiRagService.java:182`
- **修复**: `onComplete`/`onError` 中 `setContext` 后用 `finally { clearContext() }` 清除

### 2. sendDoneAndComplete 无重入保护 ✅
- **文件**: `LearningChatController.java:165`
- **修复**: 加 `AtomicBoolean doneGuard`，`compareAndSet(false, true)` 防止重入

## 🟡 严重

### 3. LIMIT 20 应为 LIMIT 10 ✅
- **文件**: `JpaChatMemoryStore.java:33`
- **修复**: `LIMIT 20` → `LIMIT 10`（1 ChatHistory = 2 ChatMessage）

### 4. SecurityException 映射为 500 而非 403 ✅
- **文件**: `ConversationService.java:47`
- **修复**: `throw new SecurityException` → `throw new ResponseStatusException(HttpStatus.FORBIDDEN, ...)`

### 5. animationDelay 无对应 CSS 动画 ✅
- **文件**: `FlowNoteView.tsx:250`
- **修复**: 移除无效的 `style={{ animationDelay }}`，entry 无动画需求

### 6. 嵌套 CompletableFuture 持有已返回的 Servlet 资源 ✅
- **文件**: `LearningChatController.java:176`
- **修复**: 嵌套 `CompletableFuture.runAsync` → 内联 `Thread.sleep(200)` + `emitter.complete()`

## ⚪ 改进

### 7. accentMap/glowMap/bgMap 每次 render 重复创建 ✅
- **文件**: `FlowNoteView.tsx:268`
- **修复**: 提取为组件外常量 `ACCENT_MAP` / `GLOW_MAP` / `HEADER_BG_MAP`

### 8. saveExchange 日志记录用户完整消息 ✅
- **文件**: `AiRagService.java:229`
- **修复**: 日志仅记录 `msgLen=N`，不记录消息内容
