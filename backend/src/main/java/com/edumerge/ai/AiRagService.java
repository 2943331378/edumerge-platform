package com.edumerge.ai;

import com.edumerge.service.ConversationService;
import com.edumerge.store.MilvusEmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * AI RAG 检索增强生成服务 (架构红线: LangChain4j 调用必须隔离在 ai 包中)
 * 负责从 Milvus 检索相关文本块，拼装防幻觉 Prompt，结合对话记忆，并调用大模型生成回答
 */
@Slf4j
@Service
public class AiRagService {

    private final EmbeddingModel embeddingModel;
    private final MilvusEmbeddingStore embeddingStore;
    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final Function<String, ChatMemory> chatMemoryProvider;
    private final ConversationService conversationService;

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Autowired
    public AiRagService(EmbeddingModel embeddingModel,
                        MilvusEmbeddingStore embeddingStore,
                        ChatLanguageModel chatLanguageModel,
                        StreamingChatLanguageModel streamingChatLanguageModel,
                        @Qualifier("chatMemoryProvider") Function<String, ChatMemory> chatMemoryProvider,
                        ConversationService conversationService) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatLanguageModel = streamingChatLanguageModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.conversationService = conversationService;
    }

    /** 同步 RAG 对话 — 带上下文记忆 */
    public AiRagResult chat(String userMessage, String documentId, String sessionId) {
        try {
            log.info("开始 RAG 流程: 问题='{}', 文档ID='{}', sessionId='{}'", userMessage, documentId, sessionId);
            List<EmbeddingMatch<TextSegment>> matches = retrieveMatches(userMessage, documentId);
            if (matches.isEmpty()) {
                return AiRagResult.empty("未在知识库中找到相关内容，无法回答该问题。");
            }

            String context = buildContext(matches);
            List<SourceReference> sources = buildSources(matches);

            // 加载对话记忆 + 拼装消息列表: System → Past Messages → Current User
            ChatMemory memory = chatMemoryProvider.apply(sessionId != null ? sessionId : "");
            List<ChatMessage> messages = buildMessagesWithMemory(userMessage, context, memory);

            log.info("调用大模型生成回答 (记忆条数={})...", memory.messages().size());
            Response<AiMessage> response = chatLanguageModel.generate(messages);
            String answer = response.content().text();

            // 确保 conversation 存在 + 将本轮问答加入记忆
            if (sessionId != null && !sessionId.isBlank()) {
                conversationService.ensure(sessionId, 1L,
                        userMessage.length() > 40 ? userMessage.substring(0, 40) + "..." : userMessage);
            }
            memory.add(new UserMessage(userMessage));
            memory.add(new AiMessage(answer));

            log.info("RAG 回答生成完成, 长度: {} 字符", answer.length());
            return AiRagResult.success(answer, sources);
        } catch (Exception e) {
            log.error("RAG 流程异常: {}", e.getMessage(), e);
            throw new RuntimeException("RAG 对话处理失败: " + e.getMessage(), e);
        }
    }

    /** 流式 RAG 对话 — 带上下文记忆 */
    public List<EmbeddingMatch<TextSegment>> chatStream(String userMessage, String documentId,
                                                         String sessionId,
                                                         StreamingResponseHandler<AiMessage> handler) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveMatches(userMessage, documentId);
        log.info("流式 RAG: 检索到 {} 个块", matches.size());

        ChatMemory memory = chatMemoryProvider.apply(sessionId != null ? sessionId : "");

        if (matches.isEmpty()) {
            String fallback = "在该材料中未找到相关内容。";
            handler.onNext(fallback);
            handler.onComplete(null);
            memory.add(new UserMessage(userMessage));
            memory.add(new AiMessage(fallback));
            return matches;
        }

        List<ChatMessage> messages = buildMessagesWithMemory(userMessage, buildContext(matches), memory);

        // 流式 handler 包装 — 累积完整回答后写入记忆
        StringBuilder fullAnswer = new StringBuilder();
        StreamingResponseHandler<AiMessage> wrappedHandler = new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                fullAnswer.append(token);
                handler.onNext(token);
            }
            @Override
            public void onComplete(Response<AiMessage> response) {
                handler.onComplete(response);
                if (sessionId != null && !sessionId.isBlank()) {
                    conversationService.ensure(sessionId, 1L,
                            userMessage.length() > 40 ? userMessage.substring(0, 40) + "..." : userMessage);
                }
                memory.add(new UserMessage(userMessage));
                memory.add(new AiMessage(fullAnswer.toString()));
            }
            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }
        };

        streamingChatLanguageModel.generate(messages, wrappedHandler);
        return matches;
    }

    /** 从 Milvus 检索匹配的文本块, 无结果时降阈值回退 */
    public List<EmbeddingMatch<TextSegment>> retrieveMatches(String userMessage, String documentId) {
        Embedding queryEmbedding = embeddingModel.embed(userMessage).content();
        Filter docFilter = buildDocumentFilter(documentId);

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(similarityThreshold)
                .filter(docFilter)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();

        if (matches.isEmpty() && similarityThreshold > 0) {
            log.info("阈值 {} 无匹配, 降阈值回退检索", similarityThreshold);
            request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(0.0)
                    .filter(docFilter)
                    .build();
            matches = embeddingStore.search(request).matches();
        }
        return matches;
    }

    /** 获取流式模型 (供外部直接调用) */
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

    /** 拼装: System Prompt (含检索上下文) + 历史对话记忆 + 当前用户问题 */
    public List<ChatMessage> buildMessagesWithMemory(String userQuestion, String context,
                                                      ChatMemory memory) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(buildSystemMessage(context));
        // 过滤掉记忆中的 SystemMessage (每轮 System 随上下文变化, 只保留对话)
        for (ChatMessage m : memory.messages()) {
            if (!(m instanceof SystemMessage)) {
                messages.add(m);
            }
        }
        messages.add(new UserMessage(userQuestion));
        return messages;
    }

    /** 构建 System Prompt — 平衡文档约束与对话连贯性 */
    public SystemMessage buildSystemMessage(String context) {
        String template = context.isBlank()
            ? """
               你是一个严谨的 AI 学习导师。
               当前未在知识库中检索到与用户问题相关的文档内容。
               请结合历史对话上下文，诚实回答"在该材料中未找到相关内容"。
               不要猜测或编造任何信息。"""
            : """
               你是一个严谨的 AI 学习导师。请结合历史对话上下文以及提供的参考文档来回答问题。

               # 优先级要求
               1. **文档为事实依据**: 必须以提供的参考文档为事实依据，严禁编造文档外的知识。
               2. **对话连贯性**: 在事实准确的基础上，结合用户的历史提问给出连贯、自然的回应。
                  例如: 用户追问"能再详细解释一下吗？"时，应回顾上一轮讨论的主题继续深入。
               3. **诚实原则**: 如果用户的问题在上下文中找不到答案，请诚实回答"在该材料中未找到相关内容"，并建议用户换个角度提问。
               4. **引用标注**: 在回答末尾标注参考的段落序号，格式为 [段落N]。
               5. **精准简洁**: 回答应精准、简洁、结构化，便于学习者理解。

               # 文档上下文
               {CONTEXT}
               """;
        String prompt = template.replace("{CONTEXT}", context);
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
