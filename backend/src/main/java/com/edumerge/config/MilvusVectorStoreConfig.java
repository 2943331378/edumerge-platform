package com.edumerge.config;

import com.edumerge.ai.JpaChatMemoryStore;
import com.edumerge.mapper.ChatHistoryMapper;
import com.edumerge.store.MilvusEmbeddingStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.MetricType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;
import java.util.function.Function;

/**
 * 向量存储及大模型配置类
 * 配置 EmbeddingModel、MilvusEmbeddingStore 和 ChatModel
 */
@Slf4j
@Configuration
public class MilvusVectorStoreConfig {

    // ===== OpenAI 兼容 API 配置 =====
    // 优先从环境变量读取，回退到 application.yml 配置
    @Value("#{systemEnvironment['DEEPSEEK_API_KEY'] ?: '${langchain4j.openai.api-key:}'}")
    private String apiKey;

    @Value("${langchain4j.openai.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${langchain4j.openai.model:deepseek-chat}")
    private String chatModelName;

    // Embedding 可单独配置 (因 DeepSeek 不支持 embeddings API)
    @Value("#{systemEnvironment['DASHSCOPE_API_KEY'] ?: '${langchain4j.openai.embedding-api-key:}'}")
    private String embeddingApiKey;

    // 内容生成模型可单独配置 API Key (默认使用 DeepSeek)
    @Value("#{systemEnvironment['DEEPSEEK_API_KEY'] ?: '${langchain4j.openai.api-key:}'}")
    private String contentApiKey;

    @Value("${langchain4j.openai.embedding-base-url:#{null}}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.openai.embedding-model:text-embedding-3-small}")
    private String embeddingModelName;

    // ===== Milvus 向量库配置 =====
    @Value("${app.rag.collection-name:edumerge_knowledge_chunks}")
    private String collectionName;

    @Value("${app.rag.embedding-dimension:1536}")
    private int embeddingDimension;

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    /**
     * 向量化模型 (OpenAI 兼容 API)
     * 支持独立配置 baseUrl 和 apiKey，未配置时回退到 chat 模型配置
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        String embBaseUrl = embeddingBaseUrl != null ? embeddingBaseUrl : baseUrl;
        String embApiKey = embeddingApiKey != null ? embeddingApiKey : apiKey;
        log.info("初始化 OpenAI Embedding 模型: {} (baseUrl={})", embeddingModelName, embBaseUrl);
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embBaseUrl)
                .apiKey(embApiKey)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 大语言聊天模型 (OpenAI 兼容 API)
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public ChatModel chatLanguageModel() {
        log.info("初始化 OpenAI Chat 模型: {} (baseUrl={})", chatModelName, baseUrl);
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(chatModelName)
                .temperature(0.1)
                .timeout(Duration.ofSeconds(300))
                .build();
    }

    /**
     * 大纲生成专用快速模型 (deepseek-v4-flash, 用于大纲/学科分类等轻量任务)
     */
    @Bean("outlineChatModel")
    public ChatModel outlineChatModel() {
        log.info("初始化大纲生成专用模型: deepseek-v4-flash (baseUrl={})", baseUrl);
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName("deepseek-v4-flash")
                .temperature(0.1)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 内容生成模型 (DeepSeek, 用于笔记/卡片/测验/思维导图)
     */
    @Bean("contentChatModel")
    public ChatModel contentChatModel(
            @Value("${app.ai.content.base-url:https://api.deepseek.com/v1}") String contentBaseUrl,
            @Value("${app.ai.content.model:deepseek-chat}") String contentModel) {
        log.info("初始化内容生成模型: {} (baseUrl={})", contentModel, contentBaseUrl);
        return OpenAiChatModel.builder()
                .baseUrl(contentBaseUrl)
                .apiKey(contentApiKey)
                .modelName(contentModel)
                .temperature(0.3)
                .maxTokens(4096)
                .topP(0.85)
                .timeout(Duration.ofSeconds(180))
                .build();
    }

    /**
     * 内容生成流式模型 (DeepSeek, 用于笔记/思维导图 SSE 流式输出)
     */
    @Bean("streamingContentChatModel")
    public StreamingChatModel streamingContentChatModel(
            @Value("${app.ai.content.base-url:https://api.deepseek.com/v1}") String contentBaseUrl,
            @Value("${app.ai.content.model:deepseek-chat}") String contentModel) {
        log.info("初始化内容生成流式模型: {} (baseUrl={})", contentModel, contentBaseUrl);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(contentBaseUrl)
                .apiKey(contentApiKey)
                .modelName(contentModel)
                .temperature(0.3)
                .maxTokens(4096)
                .topP(0.85)
                .timeout(Duration.ofSeconds(180))
                .build();
    }

    /**
     * Vision 模型 (用于图片/扫描版 PDF OCR, 复用 DashScope API Key)
     */
    @Bean("visionChatModel")
    public ChatModel visionChatModel(
            @Value("${app.ai.vision.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String visionBaseUrl,
            @Value("#{systemEnvironment['DASHSCOPE_API_KEY'] ?: '${langchain4j.openai.embedding-api-key:}'}") String visionApiKey,
            @Value("${app.ai.vision.model:qwen-vl-max}") String visionModel) {
        log.info("初始化 Vision 模型: {} (baseUrl={})", visionModel, visionBaseUrl);
        return OpenAiChatModel.builder()
                .baseUrl(visionBaseUrl)
                .apiKey(visionApiKey)
                .modelName(visionModel)
                .temperature(0.1)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 流式聊天模型 (用于 SSE 逐字推送)
     */
    @Bean
    public StreamingChatModel streamingChatLanguageModel() {
        log.info("初始化 OpenAI Streaming Chat 模型: {} (baseUrl={})", chatModelName, baseUrl);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(chatModelName)
                .temperature(0.1)
                .timeout(Duration.ofSeconds(300))
                .build();
    }

    /**
     * Milvus 向量存储 (桥接 LangChain4j 与 Milvus)
     */
    @Bean
    @Lazy
    public MilvusEmbeddingStore milvusEmbeddingStore(MilvusServiceClient milvusClient) {
        log.info("初始化 Milvus 向量存储: 集合={}, 维度={}", collectionName, embeddingDimension);
        return new MilvusEmbeddingStore(
                milvusClient,
                collectionName,
                embeddingDimension,
                MetricType.COSINE
        );
    }

    /**
     * ChatMemoryStore — MySQL 持久化对话历史
     */
    @Bean
    public ChatMemoryStore chatMemoryStore(ChatHistoryMapper chatHistoryMapper,
                                            @Value("${app.chat.memory-window:10}") int memoryWindow) {
        return new JpaChatMemoryStore(chatHistoryMapper, memoryWindow);
    }

    /**
     * ChatMemoryProvider — 每个会话保留最近 N 轮对话, 持久化到 MySQL
     * maxMessages = memoryWindow * 2 (每轮 = User + AI 两条消息)
     */
    @Bean
    public Function<String, ChatMemory> chatMemoryProvider(ChatMemoryStore chatMemoryStore,
                                                            @Value("${app.chat.memory-window:10}") int memoryWindow) {
        return (String memoryId) -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(memoryWindow * 2)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }
}
