package com.edumerge.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.ChatHistory;
import com.edumerge.entity.Document;
import com.edumerge.mapper.ChatHistoryMapper;
import com.edumerge.mapper.DocumentMapper;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.ConversationService;
import com.edumerge.service.FlowNoteService;
import com.edumerge.store.MilvusEmbeddingStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ChatModel chatLanguageModel;
    private final StreamingChatModel streamingChatLanguageModel;
    private final Function<String, ChatMemory> chatMemoryProvider;
    private final ConversationService conversationService;
    private final ChatHistoryMapper chatHistoryMapper;
    private final DocumentMapper documentMapper;
    private final FlowNoteService flowNoteService;
    private final ObjectMapper objectMapper;
    private final ExecutorService documentTaskExecutor;

    // FlowNote 自动提取：每 5 轮对话触发一次 (已持久化到 conversations.exchange_count)

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Value("${app.rag.query-rewrite.enabled:true}")
    private boolean queryRewriteEnabled;

    @Value("${app.rag.multi-query.enabled:true}")
    private boolean multiQueryEnabled;

    @Value("${app.rag.multi-query.count:2}")
    private int multiQueryCount;

    @Value("${app.rag.relevance-filter.enabled:true}")
    private boolean relevanceFilterEnabled;

    @Value("${app.rag.relevance-filter.min-relevance:0.4}")
    private double minRelevance;

    @Autowired
    public AiRagService(EmbeddingModel embeddingModel,
                        MilvusEmbeddingStore embeddingStore,
                        ChatModel chatLanguageModel,
                        StreamingChatModel streamingChatLanguageModel,
                        @Qualifier("chatMemoryProvider") Function<String, ChatMemory> chatMemoryProvider,
                        ConversationService conversationService,
                        ChatHistoryMapper chatHistoryMapper,
                        DocumentMapper documentMapper,
                        FlowNoteService flowNoteService,
                        ObjectMapper objectMapper,
                        @Qualifier("documentTaskExecutor") ExecutorService documentTaskExecutor) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatLanguageModel = streamingChatLanguageModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.conversationService = conversationService;
        this.chatHistoryMapper = chatHistoryMapper;
        this.documentMapper = documentMapper;
        this.flowNoteService = flowNoteService;
        this.objectMapper = objectMapper;
        this.documentTaskExecutor = documentTaskExecutor;
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
            ChatResponse response = AiGeneratorBase.AI_CIRCUIT_BREAKER.execute(() -> chatLanguageModel.chat(messages));
            String answer = response.aiMessage().text();

            // 提取 token 用量
            Integer tokensUsed = null;
            if (response.metadata() != null && response.metadata().tokenUsage() != null) {
                tokensUsed = (int) response.metadata().tokenUsage().totalTokenCount();
            }

            // 直接持久化 (绕过 LangChain4j 0.28.0 ChatMemory.add 消息丢失 bug)
            saveExchange(sessionId, documentId, docId, userMessage, answer, activityType, matches.size(), tokensUsed);

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
                                                         StreamingChatResponseHandler handler) {
        return chatStream(userMessage, documentId, sessionId, null, null, handler);
    }

    /** 流式 RAG 对话 — 带上下文记忆 + 活动上下文 */
    public List<EmbeddingMatch<TextSegment>> chatStream(String userMessage, String documentId,
                                                         String sessionId,
                                                         Long docId, String activityType,
                                                         StreamingChatResponseHandler handler) {
        return chatStream(userMessage, documentId, sessionId, docId, activityType, null, handler);
    }

    /** 流式 RAG 对话 — 带上下文记忆 + 活动上下文 + 步骤上下文 */
    public List<EmbeddingMatch<TextSegment>> chatStream(String userMessage, String documentId,
                                                         String sessionId,
                                                         Long docId, String activityType,
                                                         String contextHint,
                                                         StreamingChatResponseHandler handler) {
        return chatStream(userMessage, documentId, sessionId, docId, activityType, contextHint, handler, null);
    }

    /** 流式 RAG 对话 — 带上下文记忆 + 活动上下文 + 步骤上下文 + matches 回传 */
    public List<EmbeddingMatch<TextSegment>> chatStream(String userMessage, String documentId,
                                                         String sessionId,
                                                         Long docId, String activityType,
                                                         String contextHint,
                                                         StreamingChatResponseHandler handler,
                                                         AtomicReference<List<EmbeddingMatch<TextSegment>>> matchesOut) {
        List<EmbeddingMatch<TextSegment>> matches;
        try {
            matches = retrieveMatches(userMessage, documentId);
        } catch (Exception e) {
            log.error("流式 RAG 检索异常: {}", e.getMessage(), e);
            String errorMsg = "检索失败: " + e.getMessage();
            handler.onPartialResponse(errorMsg);
            handler.onCompleteResponse(null);
            saveExchange(sessionId, documentId, docId, userMessage, errorMsg, activityType, 0, null);
            return List.of();
        }
        log.info("流式 RAG: 检索到 {} 个块, activityType={}, contextHint={}", matches.size(), activityType, contextHint);

        // 回传 matches 给调用方（在 handler 回调前设好，避免竞态）
        if (matchesOut != null) matchesOut.set(matches);

        ChatMemory memory = chatMemoryProvider.apply(sessionId != null ? sessionId : "");

        if (matches.isEmpty()) {
            String fallback = "在该材料中未找到相关内容。";
            handler.onPartialResponse(fallback);
            handler.onCompleteResponse(null);
            saveExchange(sessionId, documentId, docId, userMessage, fallback, activityType, 0, null);
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
        final SecurityContext securityContext =
                SecurityContextHolder.getContext();
        StringBuilder fullAnswer = new StringBuilder();
        StreamingChatResponseHandler wrappedHandler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                fullAnswer.append(token);
                handler.onPartialResponse(token);
            }
            @Override
            public void onCompleteResponse(ChatResponse response) {
                SecurityContextHolder.setContext(securityContext);
                try {
                    log.info("流式生成完成, 持久化: sessionId={}, answerLen={}", sessionId, fullAnswer.length());
                    handler.onCompleteResponse(response);
                    Integer tokensUsed = null;
                    if (response != null && response.metadata() != null && response.metadata().tokenUsage() != null) {
                        tokensUsed = (int) response.metadata().tokenUsage().totalTokenCount();
                    }
                    saveExchange(sessionId, finalDocId, finalDocId2, userMessage, fullAnswer.toString(), finalActivityType, finalRetrievedCount, tokensUsed);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
            @Override
            public void onError(Throwable error) {
                SecurityContextHolder.setContext(securityContext);
                try {
                    log.error("流式生成错误: sessionId={}, error={}", sessionId, error.getMessage(), error);
                    handler.onError(error);
                    saveExchange(sessionId, finalDocId, finalDocId2, userMessage, "生成失败: " + error.getMessage(), finalActivityType, finalRetrievedCount, null);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        };

        streamingChatLanguageModel.chat(messages, wrappedHandler);
        return matches;
    }

    /** 从 Milvus 检索匹配的文本块 — 增强管线: 查询改写 → 多查询检索 → 相关性过滤 */
    public List<EmbeddingMatch<TextSegment>> retrieveMatches(String userMessage, String documentId) {
        long start = System.currentTimeMillis();
        Filter docFilter = buildDocumentFilter(documentId);

        // 步骤 1+2: 查询改写 + 变体生成 (合并为单次 LLM 调用减少延迟)
        List<String> queries;
        if (queryRewriteEnabled || multiQueryEnabled) {
            queries = expandQuery(userMessage, multiQueryEnabled ? multiQueryCount : 1);
        } else {
            queries = List.of(userMessage);
        }
        String searchQuery = queries.get(0);

        // 步骤 3: 并行嵌入 + 检索, 合并去重
        List<List<EmbeddingMatch<TextSegment>>> allResults;
        if (queries.size() > 1) {
            List<CompletableFuture<List<EmbeddingMatch<TextSegment>>>> futures = new ArrayList<>();
            for (String q : queries) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> searchMilvus(q, docFilter, topK), documentTaskExecutor));
            }
            allResults = new ArrayList<>();
            for (var f : futures) {
                try { allResults.add(f.get()); } catch (Exception e) { log.warn("并行检索异常: {}", e.getMessage()); }
            }
        } else {
            allResults = List.of(searchMilvus(queries.get(0), docFilter, topK));
        }

        List<EmbeddingMatch<TextSegment>> allMatches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<EmbeddingMatch<TextSegment>> qMatches : allResults) {
            for (EmbeddingMatch<TextSegment> m : qMatches) {
                TextSegment seg = m.embedded();
                String key = seg.metadata().getString("document_id") + ":" + seg.metadata().getInteger("chunk_index");
                if (seen.add(key)) {
                    allMatches.add(m);
                }
            }
        }

        // 按 score 降序排列, 取 top-K
        allMatches.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (allMatches.size() > topK) {
            allMatches = new ArrayList<>(allMatches.subList(0, topK));
        }

        // 步骤 4: 相关性过滤 (可选)
        if (relevanceFilterEnabled && allMatches.size() > 1) {
            allMatches = filterByRelevance(userMessage, allMatches);
        }

        log.info("增强检索完成: query='{}', variants={}, matches={}, 耗时={}ms",
                truncateForLog(searchQuery), queries.size(), allMatches.size(), System.currentTimeMillis() - start);
        return allMatches;
    }

    /** Milvus 单次向量检索 (带阈值回退) */
    private List<EmbeddingMatch<TextSegment>> searchMilvus(String query, Filter docFilter, int k) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(k)
                .minScore(similarityThreshold)
                .filter(docFilter)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();

        if (matches.isEmpty() && similarityThreshold > 0) {
            log.debug("阈值 {} 无匹配, 降阈值回退: query='{}'", similarityThreshold, truncateForLog(query));
            request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(k)
                    .minScore(0.0)
                    .filter(docFilter)
                    .build();
            matches = embeddingStore.search(request).matches();
        }
        return matches;
    }

    /**
     * 用 LLM 一次性完成查询改写 + 变体生成 (单次调用减少延迟)
     * 返回列表: 第 0 个为改写后的主查询, 后续为变体
     */
    private List<String> expandQuery(String userMessage, int totalCount) {
        try {
            String prompt = totalCount <= 1
                    ? """
                    你是搜索查询优化器。将用户问题改写为更适合向量语义检索的形式。
                    规则: 保留原意但更精确；去除口语化表达(如"帮我""能不能""讲讲")；补充可推断的同义术语；已是精确技术问题则原样返回。仅输出改写结果，不解释。

                    用户问题: %s""".formatted(userMessage)
                    : """
                    你是搜索查询优化器。请完成两件事:
                    1. 将用户问题改写为更适合向量语义检索的形式 (主查询)
                    2. 再生成 %d 个语义等价但表述不同的变体

                    规则: 第一行为主查询，后续行为变体；保持原意但换用不同术语/句式；覆盖同义词和上位/下位概念。每行一个查询，不编号不解释。

                    用户问题: %s""".formatted(totalCount - 1, userMessage);

            ChatResponse response = AiGeneratorBase.AI_CIRCUIT_BREAKER.execute(() -> chatLanguageModel.chat(new UserMessage(prompt)));
            if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
                return List.of(userMessage);
            }
            String text = response.aiMessage().text().trim();
            String[] lines = text.split("\n");
            List<String> queries = new ArrayList<>();
            for (String line : lines) {
                String q = line.trim().replaceAll("^\\d+[.、)\\s]+", "");
                if (!q.isEmpty() && queries.size() < totalCount) {
                    queries.add(q);
                }
            }
            if (queries.isEmpty()) queries.add(userMessage);
            while (queries.size() < totalCount) queries.add(userMessage);
            log.info("查询扩展: 原始='{}', 生成{}个查询", truncateForLog(userMessage), queries.size());
            return queries;
        } catch (Exception e) {
            log.warn("查询扩展失败, 使用原始查询: {}", e.getMessage());
            return List.of(userMessage);
        }
    }

    /** 用 LLM 对检索结果进行相关性评分，过滤低质量片段 */
    private List<EmbeddingMatch<TextSegment>> filterByRelevance(String userMessage,
                                                                  List<EmbeddingMatch<TextSegment>> matches) {
        try {
            // 构建片段列表供 LLM 评分
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < matches.size(); i++) {
                String text = matches.get(i).embedded().text();
                sb.append("[片段%d] %s\n\n".formatted(i + 1, truncate(text, 300)));
            }

            String prompt = """
                    你是相关性评分器。给定用户问题和若干文档片段，为每个片段评分(0-1)。
                    评分标准: 0=完全无关, 0.3=边缘相关, 0.5=部分相关, 0.7=相关, 1.0=高度相关。

                    用户问题: %s

                    片段列表:
                    %s
                    仅输出JSON数组，格式: [{"id":1,"score":0.8},{"id":2,"score":0.3},...]""".formatted(userMessage, sb);

            ChatResponse response = AiGeneratorBase.AI_CIRCUIT_BREAKER.execute(() -> chatLanguageModel.chat(new UserMessage(prompt)));
            if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
                log.warn("相关性过滤: LLM 返回空响应, 保留原始结果");
                return matches;
            }
            String json = extractJsonArray(response.aiMessage().text());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scores = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});

            // 按原始顺序收集通过阈值的片段 ID (去重, 保持 embedding score 排序)
            Set<Integer> usedIds = new HashSet<>();
            List<EmbeddingMatch<TextSegment>> filtered = new ArrayList<>();
            for (Map<String, Object> item : scores) {
                int id = item.get("id") instanceof Number ? ((Number) item.get("id")).intValue() : 0;
                double score = item.get("score") instanceof Number ? ((Number) item.get("score")).doubleValue() : 0;
                if (id >= 1 && id <= matches.size() && score >= minRelevance && usedIds.add(id)) {
                    filtered.add(matches.get(id - 1));
                }
            }

            if (filtered.isEmpty()) {
                log.info("相关性过滤: 所有片段均低于阈值 {}, 保留全部 {} 个", minRelevance, matches.size());
                return matches;
            }
            log.info("相关性过滤: {} -> {} 个片段 (阈值={})", matches.size(), filtered.size(), minRelevance);
            return filtered;
        } catch (Exception e) {
            log.warn("相关性过滤失败, 保留原始结果: {}", e.getMessage());
            return matches;
        }
    }

    /** 获取流式模型 (供外部直接调用) */
    public StreamingChatModel getStreamingModel() { return streamingChatLanguageModel; }

    private String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen ? text.substring(0, maxLen - 3) + "..." : text;
    }

    private String truncateForLog(String text) {
        return text != null && text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }

    private String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw;
    }

    /** 直接持久化一轮对话 (绕过 LangChain4j 0.28.0 ChatMemory.add 消息丢失 bug) */
    private void saveExchange(String sessionId, String documentId, Long docId,
                               String userMessage, String aiResponse, String activityType,
                               int retrievedCount, Integer tokensUsed) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            String title = userMessage.length() > 40 ? userMessage.substring(0, 40) + "..." : userMessage;
            log.info("saveExchange: sessionId={}, docId={}, userId={}, msgLen={}, activityType={}, retrievedCount={}, tokensUsed={}",
                    sessionId, docId, userId, userMessage.length(), activityType, retrievedCount, tokensUsed);
            conversationService.ensure(sessionId, userId, title, docId, documentId);
            ChatHistory record = ChatHistory.builder()
                    .userId(userId).sessionId(sessionId)
                    .query(userMessage).response(aiResponse)
                    .retrievedDocuments(retrievedCount).tokensUsed(tokensUsed).deleted(0)
                    .activityType(activityType)
                    .build();
            chatHistoryMapper.insert(record);
            log.info("对话已持久化: sessionId={}, id={}, activityType={}, tokensUsed={}", sessionId, record.getId(), activityType, tokensUsed);

            // FlowNote 自动提取: 每5轮对话触发一次 (DB 持久化计数)
            tryAutoExtractFlowNote(sessionId, userId, documentId, docId);
        } catch (Exception e) {
            log.error("对话持久化失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }

    /** FlowNote 自动提取：每积累 5 轮对话，AI 自动提取学习要点 */
    private void tryAutoExtractFlowNote(String sessionId, Long userId, String documentId,
                                         Long docId) {
        try {
            int count = conversationService.incrementExchangeCount(sessionId);
            if (count % 5 != 0) return;

            // 优先用 docId（数据库主键）查找文档，回退到 documentId (Milvus UUID)
            Document doc = null;
            if (docId != null) {
                doc = documentMapper.selectById(docId);
            } else if (documentId != null && !documentId.isBlank()) {
                doc = documentMapper.selectOne(
                        new LambdaQueryWrapper<Document>()
                                .eq(Document::getDocumentId, documentId));
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

    /** 拼装: System Prompt (静态指令) + 历史对话记忆 + 当前用户问题 (含动态上下文) */
    public List<ChatMessage> buildMessagesWithMemory(String userQuestion, String context,
                                                      String stepContext, ChatMemory memory) {
        List<ChatMessage> messages = new ArrayList<>();
        // SystemMessage: 仅静态指令（prefix cache 友好，同文档会话共享同一 prefix）
        messages.add(buildSystemMessage(context));
        for (ChatMessage m : memory.messages()) {
            if (!(m instanceof SystemMessage)) {
                messages.add(m);
            }
        }
        // UserMessage: 动态内容（文档上下文 + 步骤上下文 + 用户问题）
        StringBuilder userSb = new StringBuilder();
        if (!context.isBlank()) {
            userSb.append("# 参考文档\n").append(context).append("\n");
        }
        if (stepContext != null && !stepContext.isBlank()) {
            userSb.append("# 用户当前活动上下文\n").append(stepContext).append("\n");
            userSb.append("请结合此上下文理解用户的问题。如果用户使用“这个”、“这里”、“刚才那个”等指示代词，请根据上述活动上下文推断其所指。\n\n");
        }
        userSb.append(userQuestion);
        messages.add(new UserMessage(userSb.toString()));
        return messages;
    }

    /** 构建 System Prompt — 仅静态指令（不包含动态上下文，最大化 prefix cache 命中率） */
    public SystemMessage buildSystemMessage(String context) {
        String prompt = context.isBlank()
            ? """
               你是一个严谨的 AI 学习导师。
               当前未在知识库中检索到与用户问题相关的文档内容。
               无论用户问题或历史对话使用什么语言，最终都必须使用简体中文回答。
               请结合历史对话上下文，诚实回答"在该材料中未找到相关内容"。
               不要猜测或编造任何信息。"""
            : """
               你是一个严谨的 AI 学习导师。结合历史对话和参考文档回答问题。

               # 核心规则
               1. 以参考文档为事实依据，严禁编造
               2. 找不到答案时诚实回答"在该材料中未找到相关内容"，并建议换个角度提问
               3. 回答末尾标注参考段落序号 [段落N]
               4. 使用简体中文；英文文档翻译归纳，术语首次保留原词如"形成性评价（formative assessment）"
               5. 结合历史对话保持连贯性（如追问时回顾上轮主题）
               6. 回答精准简洁、结构化；总结类问题按"概述→核心知识点→可复习问题"组织
               7. 面向深度理解，回答要覆盖"为什么"和"怎么用"，不要停留在"是什么"层面
               8. 涉及对比、辨析、应用场景时，用具体例子说明而非泛泛而谈""";
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
