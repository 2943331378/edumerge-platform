# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

### Backend (Spring Boot 3.2.4 + Java 17)

```bash
# Build (must separate clean and package — combining them causes resource packaging race on Windows)
cd backend
mvn clean
mvn package -DskipTests

# Run (NOT mvn spring-boot:run — that hits the Windows 32767-char classpath limit)
java -Dfile.encoding=UTF-8 -jar target/edumerge-backend-1.0.0.jar

# To force-kill a lingering Java process (Windows)
powershell -Command "Stop-Process -Name java -Force"
```

### Frontend (Next.js 16.2.4 + shadcn/ui + Tailwind v4)

```bash
cd frontend
npm run dev       # dev server on localhost:3000
npm run build     # production build
```

## Architecture

### Backend Layers

```
controller/          → REST endpoints (Result<T> unified response)
  service/           → Business logic (RagChatService: the RAG pipeline)
  mq/
    producer/        → RabbitMQ message senders (EmbeddingProducer)
    listener/        → @RabbitListener consumers (DocumentListener: async PDF vectorization)
    message/         → DTOs passed over RabbitMQ
  config/            → Spring @Configuration beans (Milvus, RabbitMQ, Redis, CORS, MyBatis-Plus)
  store/             → MilvusEmbeddingStore — custom LangChain4j EmbeddingStore<TextSegment> backed by Milvus SDK 2.4.4
  entity/            → MyBatis-Plus @TableName entities (User, Document, ChatHistory)
  mapper/            → MyBatis-Plus BaseMapper interfaces (no XML needed)
  common/result/     → Result<T> unified API response wrapper (code=0 success, 4xx/5xx failures)
```

### RAG Pipeline (RagChatService.chat)

```
User query → EmbeddingModel.embed() (DashScope text-embedding-v3 via OpenAI-compatible API)
           → MilvusEmbeddingStore.search() with similarity threshold + top-K
           → Build anti-hallucination system prompt ("仅基于参考文献回答")
           → ChatLanguageModel.generate() (DeepSeek deepseek-chat via OpenAI-compatible API)
           → Return answer + source references
```

### Async Document Vectorization (RabbitMQ)

```
POST /api/documents/upload → saves PDF → EmbeddingProducer.sendEmbeddingTask()
  → RabbitMQ EMBEDDING_QUEUE → DocumentListener.handleEmbeddingTask()
      → Apache PDFBox extracts text
      → DocumentSplitters.recursive(500, 50) chunks
      → EmbeddingModel embeds each chunk
      → MilvusEmbeddingStore.addAll() stores to Milvus with metadata (document_id, chunk_index)
```

### Milvus Collection Schema

Collection `edumerge_knowledge_chunks`: `id` (Int64 PK auto), `document_id` (VarChar 128), `chunk_index` (Int32), `text` (VarChar 65535), `embedding` (FloatVector 1024)

### Model Configuration

In `MilvusVectorStoreConfig`: Chat → DeepSeek (`deepseek-chat` at api.deepseek.com/v1). Embedding → DashScope (`text-embedding-v3` at dashscope.aliyuncs.com/compatible-mode/v1). Both use `OpenAiChatModel`/`OpenAiEmbeddingModel` builders with custom base URLs. The embedding API key and base URL are separately configurable (DeepSeek doesn't support embeddings).

## Known Pitfalls

### Build

- **`mvn clean package` together**: On Windows, resources may not be packaged into the jar. Always run `mvn clean` then `mvn package -DskipTests` as separate commands.
- **`mvn spring-boot:run`**: Fails with `CreateProcess error=206` on Windows — the classpath string exceeds 32767 chars. Use `java -jar` instead.
- **Jar file lock**: After `java -jar &` in background, kill via `powershell -Command "Stop-Process -Name java -Force"` before rebuild. `taskkill /F /IM java.exe` on Windows Chinese edition often fails silently.

### Dependencies

- **mybatis-spring**: Must be ≥ 3.0.4 for Spring 6.1 compatibility. MyBatis-Plus 3.5.7 pulls in 2.1.2 transitively — excluded and overridden to 3.0.5 in pom.xml.
- **LangChain4j `Document` class**: `dev.langchain4j.data.document.Document` vs our `com.edumerge.entity.Document` — avoid importing the wrong one.
- **Next.js 16**: APIs differ from Next 14/15 training data. Check `node_modules/next/dist/docs/` before writing code.

### Runtime

- **Console encoding (Windows Chinese)**: Run `chcp 65001` before `java -jar`, and pass `-Dfile.encoding=UTF-8`. logback-spring.xml already sets `<charset>UTF-8</charset>`.
- **Milvus collection**: Created with auto-index (IVF_FLAT, nlist=128) and loaded to memory in `ensureCollection()`. Dropping via REST API may not work — use Milvus Attu UI.
- **Empty search results**: `SearchResultsWrapper.getFieldData()` throws `ParamException` if `idScores` is empty (no fields data). `MilvusEmbeddingStore.search()` checks for empty results before calling `getFieldData`.
