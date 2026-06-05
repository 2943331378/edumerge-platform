package com.edumerge.ai;

import com.edumerge.entity.ChatHistory;
import com.edumerge.mapper.ChatHistoryMapper;
import com.edumerge.mapper.DocumentMapper;
import com.edumerge.service.ConversationService;
import com.edumerge.service.FlowNoteService;
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
    private final ChatHistoryMapper chatHistoryMapper;
    private final DocumentMapper documentMapper;
    private final FlowNoteService flowNoteService;

    // FlowNote 自动提取：每 5 轮对话触发一次
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> exchangeCounters = new java.util.concurrent.ConcurrentHashMap<>();

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
                        ConversationService conversationService,
                        ChatHistoryMapper chatHistoryMapper,
                        DocumentMapper documentMapper,
                        FlowNoteService flowNoteService) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatLanguageModel = streamingChatLanguageModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.conversationService = conversationService;
        this.chatHistoryMapper = chatHistoryMapper;
        this.documentMapper = documentMapper;
        this.flowNoteService = flowNoteService;
    }

    /** 同步 RAG 对话 — 带上下文记忆 */
    public AiRagResult chat(String userMessage, String documentId, String sessionId) {
        return chat(userMessage, documentId, sessionId, null, null);
    }

    /** 同步 RAG 对话 — 带上下文记忆 + 活动上下文 */
    public AiRagResult chat(String userMessage, String documentId, String sessionId,
                             Long docId, String activityType) {
        return chat(userMessage, documentId, sessionId, docId, activityType, null);
    }

    /** 同步 RAG 对话 — 带上下文记忆 + 活动上下文 + 步骤上下文 */
    public AiRagResult chat(String userMessage, String documentId, String sessionId,
                             Long docId, String activityType, String contextHint) {
        try {
            log.info("开始 RAG 流程: 问题='{}', 文档ID='{}', activityType='{}', contextHint='{}'",
                    userMessage, documentId, activityType, contextHint);
            List<EmbeddingMatch<TextSegment>> matches = retrieveMatches(userMessage, documentId);
            if (matches.isEmpty()) {
                return AiRagResult.empty("未在知识库中找到相关内容，无法回答该问题。");
            }

            String context = buildContext(matches);
            List<SourceReference> sources = buildSources(matches);

            // 加载对话记忆 + 拼装消息列表: System → Past Messages → Current User
            ChatMemory memory = chatMemoryProvider.apply(sessionId != null ? sessionId : "");
            List<ChatMessage> messages = buildMessagesWithMemory(userMessage, context, contextHint, memory);

            log.info("调用大模型生成回答 (记忆条数={})...", memory.messages().size());
            Response<AiMessage> response = chatLanguageModel.generate(messages);
            String answer = response.content().text();

            // 直接持久化 (绕过 LangChain4j 0.28.0 ChatMemory.add 消息丢失 bug)
            saveExchange(sessionId, documentId, docId, userMessage, answer, activityType, matches.size());

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
        return chatStream(userMessage, documentId, sessionId, null, null, handler);
    }

    /** 流式 RAG 对话 — 带上下文记忆 + 活动上下文 */
    public List<EmbeddingMatch<TextSegment>> chatStream(String userMessage, String documentId,
                                                         String sessionId,
                                                         Long docId, String activityType,
                                                         StreamingResponseHandler<AiMessage> handler) {
        return chatStream(userMessage, documentId, sessionId, docId, activityType, null, handler);
    }

    /** 流式 RAG 对话 — 带上下文记忆 + 活动上下文 + 步骤上下文 */
    public List<EmbeddingMatch<TextSegment>> chatStream(String userMessage, String documentId,
                                                         String sessionId,
                                                         Long docId, String activityType,
                                                         String contextHint,
                                                         StreamingResponseHandler<AiMessage> handler) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveMatches(userMessage, documentId);
        log.info("流式 RAG: 检索到 {} 个块, activityType={}, contextHint={}", matches.size(), activityType, contextHint);

        ChatMemory memory = chatMemoryProvider.apply(sessionId != null ? sessionId : "");

        if (matches.isEmpty()) {
            String fallback = "在该材料中未找到相关内容。";
            handler.onNext(fallback);
            handler.onComplete(null);
            saveExchange(sessionId, documentId, docId, userMessage, fallback, activityType, 0);
            return matches;
        }

        List<ChatMessage> messages = buildMessagesWithMemory(userMessage, buildContext(matches), contextHint, memory);

        // 流式 handler 包装
        final String finalDocId = documentId;
        final Long finalDocId2 = docId;
        final String finalActivityType = activityType;
        final int finalRetrievedCount = matches.size();
        // 捕获当前线程的 SecurityContext（来自控制器的 CompletableFuture 线程），
        // 因为 onComplete/onError 由 LLM 提供商的线程调用，不继承 ThreadLocal
        final org.springframework.security.core.context.SecurityContext securityContext =
                org.springframework.security.core.context.SecurityContextHolder.getContext();
        StringBuilder fullAnswer = new StringBuilder();
        StreamingResponseHandler<AiMessage> wrappedHandler = new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                fullAnswer.append(token);
                handler.onNext(token);
            }
            @Override
            public void onComplete(Response<AiMessage> response) {
                org.springframework.security.core.context.SecurityContextHolder.setContext(securityContext);
                try {
                    log.info("流式生成完成, 持久化: sessionId={}, answerLen={}", sessionId, fullAnswer.length());
                    handler.onComplete(response);
                    saveExchange(sessionId, finalDocId, finalDocId2, userMessage, fullAnswer.toString(), finalActivityType, finalRetrievedCount);
                } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
            }
            @Override
            public void onError(Throwable error) {
                org.springframework.security.core.context.SecurityContextHolder.setContext(securityContext);
                try {
                    log.error("流式生成错误: sessionId={}", sessionId, error.getMessage());
                    handler.onError(error);
                    saveExchange(sessionId, finalDocId, finalDocId2, userMessage, "生成失败: " + error.getMessage(), finalActivityType, finalRetrievedCount);
                } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
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

    /** 直接持久化一轮对话 (绕过 LangChain4j 0.28.0 ChatMemory.add 消息丢失 bug) */
    private void saveExchange(String sessionId, String documentId, Long docId,
                               String userMessage, String aiResponse, String activityType,
                               int retrievedCount) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            Long userId = com.edumerge.security.SecurityUtils.getCurrentUserId();
            String title = userMessage.length() > 40 ? userMessage.substring(0, 40) + "..." : userMessage;
            log.info("saveExchange: sessionId={}, docId={}, userId={}, msgLen={}, activityType={}, retrievedCount={}",
                    sessionId, docId, userId, userMessage.length(), activityType, retrievedCount);
            conversationService.ensure(sessionId, userId, title, docId);
            ChatHistory record = ChatHistory.builder()
                    .userId(userId).sessionId(sessionId)
                    .query(userMessage).response(aiResponse)
                    .retrievedDocuments(retrievedCount).deleted(0)
                    .activityType(activityType)
                    .build();
            chatHistoryMapper.insert(record);
            log.info("对话已持久化: sessionId={}, id={}, activityType={}", sessionId, record.getId(), activityType);

            // FlowNote 自动提取: 每5轮对话触发一次
            tryAutoExtractFlowNote(sessionId, userId, documentId, docId);
        } catch (Exception e) {
            log.error("对话持久化失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }

    /** FlowNote 自动提取：每积累 5 轮对话，AI 自动提取学习要点 */
    private void tryAutoExtractFlowNote(String sessionId, Long userId, String documentId,
                                         Long docId) {
        try {
            String counterKey = userId + ":" + sessionId;
            int count = exchangeCounters.merge(counterKey, 1, Integer::sum);
            if (count % 5 != 0) return;

            // 优先用 docId（数据库主键）查找文档，回退到 documentId (Milvus UUID)
            com.edumerge.entity.Document doc = null;
            if (docId != null) {
                doc = documentMapper.selectById(docId);
            } else if (documentId != null && !documentId.isBlank()) {
                doc = documentMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.edumerge.entity.Document>()
                                .eq(com.edumerge.entity.Document::getDocumentId, documentId));
            }
            if (doc == null || doc.getId() == null) return;

            flowNoteService.extractFromChat(doc.getId(), doc.getDocumentId(), userId, sessionId, 10);
            log.info("FlowNote 自动提取触发: sessionId={}, docId={}, exchangeCount={}", sessionId, doc.getId(), count);
        } catch (Exception e) {
            log.debug("FlowNote 自动提取跳过: {}", e.getMessage());
        }
    }

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

    /** 拼装: System Prompt (含检索上下文+步骤上下文) + 历史对话记忆 + 当前用户问题 */
    public List<ChatMessage> buildMessagesWithMemory(String userQuestion, String context,
                                                      String stepContext, ChatMemory memory) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(buildSystemMessage(context, stepContext));
        for (ChatMessage m : memory.messages()) {
            if (!(m instanceof SystemMessage)) {
                messages.add(m);
            }
        }
        messages.add(new UserMessage(userQuestion));
        return messages;
    }

    /** 构建 System Prompt — 平衡文档约束与对话连贯性，注入步骤上下文 */
    public SystemMessage buildSystemMessage(String context, String stepContext) {
        String baseTemplate = context.isBlank()
            ? """
               你是一个严谨的 AI 学习导师。
               当前未在知识库中检索到与用户问题相关的文档内容。
               无论用户问题或历史对话使用什么语言，最终都必须使用简体中文回答。
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
               6. **中文输出**: 无论参考文档是英文还是其他语言，最终回答必须使用简体中文。
               7. **英文文档处理**: 如果参考文档为英文，请基于英文原文进行中文解释、归纳和转述；关键术语首次出现时保留英文原词，例如"形成性评价（formative assessment）"。
               8. **知识点提炼**: 当用户询问总结、解释、学习要点或"这篇文档讲了什么"时，优先按"中文概述"、"核心知识点"、"可复习问题"组织回答。

               # 文档上下文
               {CONTEXT}""";

        // 注入步骤级上下文（用户当前正在查看的内容）
        if (stepContext != null && !stepContext.isBlank()) {
            baseTemplate += """

               # 用户当前活动上下文
               {STEP_CONTEXT}
               请结合此上下文理解用户的问题。如果用户使用"这个"、"这里"、"刚才那个"等指示代词，请根据上述活动上下文推断其所指。""";
        }

        String prompt = baseTemplate.replace("{CONTEXT}", context);
        if (stepContext != null && !stepContext.isBlank()) {
            prompt = prompt.replace("{STEP_CONTEXT}", stepContext);
        }
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
