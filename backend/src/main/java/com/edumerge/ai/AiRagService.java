package com.edumerge.ai;

import com.edumerge.store.MilvusEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI RAG 检索增强生成服务 (架构红线: LangChain4j 调用必须隔离在 ai 包中)
 * 负责从 Milvus 检索相关文本块，拼装防幻觉 Prompt，并调用大模型生成回答
 */
@Slf4j
@Service
public class AiRagService {

    private final EmbeddingModel embeddingModel;
    private final MilvusEmbeddingStore embeddingStore;
    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Autowired
    public AiRagService(EmbeddingModel embeddingModel,
                        MilvusEmbeddingStore embeddingStore,
                        ChatLanguageModel chatLanguageModel,
                        StreamingChatLanguageModel streamingChatLanguageModel) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatLanguageModel = streamingChatLanguageModel;
    }

    /** 同步 RAG 对话 */
    public AiRagResult chat(String userMessage, String documentId) {
        try {
            log.info("开始 RAG 流程: 问题='{}', 文档ID='{}'", userMessage, documentId);
            List<EmbeddingMatch<TextSegment>> matches = retrieveMatches(userMessage, documentId);
            if (matches.isEmpty()) {
                return AiRagResult.empty("未在知识库中找到相关内容，无法回答该问题。");
            }

            String context = buildContext(matches);
            List<SourceReference> sources = buildSources(matches);
            List<ChatMessage> messages = buildMessages(userMessage, context);

            log.info("调用大模型生成回答...");
            Response<AiMessage> response = chatLanguageModel.generate(messages);
            String answer = response.content().text();

            log.info("RAG 回答生成完成, 长度: {} 字符", answer.length());
            return AiRagResult.success(answer, sources);
        } catch (Exception e) {
            log.error("RAG 流程异常: {}", e.getMessage(), e);
            throw new RuntimeException("RAG 对话处理失败: " + e.getMessage(), e);
        }
    }

    /** 流式 RAG 对话 — 通过 StreamingChatLanguageModel 逐字生成 */
    public List<EmbeddingMatch<TextSegment>> chatStream(String userMessage, String documentId,
                                                         StreamingResponseHandler<AiMessage> handler) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveMatches(userMessage, documentId);
        log.info("流式 RAG: 检索到 {} 个块", matches.size());

        if (matches.isEmpty()) {
            handler.onNext("在该材料中未找到相关内容。");
            handler.onComplete(null);
            return matches;
        }

        List<ChatMessage> messages = buildMessages(userMessage, buildContext(matches));
        streamingChatLanguageModel.generate(messages, handler);
        return matches;
    }

    /** 从 Milvus 检索匹配的文本块 */
    public List<EmbeddingMatch<TextSegment>> retrieveMatches(String userMessage, String documentId) {
        Embedding queryEmbedding = embeddingModel.embed(userMessage).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(similarityThreshold)
                .filter(buildDocumentFilter(documentId))
                .build();
        return embeddingStore.search(request).matches();
    }

    /** 获取流式模型 (供 Controller 直接调用) */
    public StreamingChatLanguageModel getStreamingModel() { return streamingChatLanguageModel; }

    /** 拼接检索到的文本块作为上下文 */
    public String buildContext(List<EmbeddingMatch<TextSegment>> matches) {
        if (matches.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            sb.append("【段落").append(i + 1).append("】\n");
            sb.append(matches.get(i).embedded().text()).append("\n\n");
        }
        return sb.toString();
    }

    /** 拼装防幻觉 System Prompt + User Message */
    public List<ChatMessage> buildMessages(String userQuestion, String context) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(buildSystemMessage(context));
        messages.add(new UserMessage(userQuestion));
        return messages;
    }

    /** 构建防幻觉 System Prompt */
    public SystemMessage buildSystemMessage(String context) {
        String prompt = context.isBlank()
            ? """
               你是一个基于特定文档工作的 AI 导师。
               当前未在知识库中检索到与用户问题相关的文档内容。
               请诚实回答"在该材料中未找到相关内容"。
               不要猜测或编造任何信息。"""
            : """
               你是一个基于特定文档工作的 AI 导师。

               # 严格规则 (必须遵守)
               1. **仅基于上下文回答**: 你的回答必须且只能参考下面提供的文档上下文内容。
               2. **诚实原则**: 如果用户的问题在上下文中找不到答案，请诚实回答"在该材料中未找到相关内容"。
               3. **禁止编造**: 即使是常识性问题，只要上下文没有提及，就不能回答。
               4. **引用标注**: 在回答末尾标注参考的段落序号，格式为 [段落N]。
               5. **精准简洁**: 回答应精准、简洁、结构化，便于学习者理解。

               # 文档上下文
               %s""".formatted(context);
        return new SystemMessage(prompt);
    }

    private Filter buildDocumentFilter(String documentId) {
        if (documentId != null && !documentId.isBlank()) return new IsEqualTo("document_id", documentId);
        return null;
    }

    private List<SourceReference> buildSources(List<EmbeddingMatch<TextSegment>> matches) {
        List<SourceReference> sources = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> m = matches.get(i);
            TextSegment s = m.embedded();
            sources.add(new SourceReference(i + 1,
                    s.metadata().getString("document_id"),
                    s.metadata().getInteger("chunk_index"), s.text(), m.score()));
        }
        return sources;
    }

    // ===== 结果封装 =====

    public static class AiRagResult {
        private final boolean success;
        private final String answer;
        private final List<SourceReference> sources;
        private final String message;
        private AiRagResult(boolean s, String a, List<SourceReference> sr, String m) {
            this.success = s; this.answer = a; this.sources = sr; this.message = m;
        }
        public static AiRagResult success(String a, List<SourceReference> s) { return new AiRagResult(true, a, s, null); }
        public static AiRagResult empty(String m) { return new AiRagResult(false, null, Collections.emptyList(), m); }
        public boolean isSuccess() { return success; }
        public String getAnswer() { return answer; }
        public List<SourceReference> getSources() { return sources; }
        public String getMessage() { return message; }
    }

    public static class SourceReference {
        private final int index;
        private final String documentId;
        private final int chunkIndex;
        private final String content;
        private final double score;
        public SourceReference(int idx, String did, int ci, String c, double s) {
            this.index = idx; this.documentId = did; this.chunkIndex = ci; this.content = c; this.score = s;
        }
        public int getIndex() { return index; }
        public String getDocumentId() { return documentId; }
        public int getChunkIndex() { return chunkIndex; }
        public String getContent() { return content; }
        public double getScore() { return score; }
    }
}
