package com.edumerge.ai;

import com.edumerge.store.MilvusEmbeddingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * AI 生成器基类 — 提供 Milvus 检索、上下文拼装、JSON 提取等公共工具方法
 */
@Slf4j
public abstract class AiGeneratorBase {

    @Autowired
    protected EmbeddingModel embeddingModel;

    @Autowired
    protected MilvusEmbeddingStore embeddingStore;

    @Autowired
    protected ObjectMapper objectMapper;

    @Value("${app.rag.similarity-threshold:0.7}")
    protected double similarityThreshold;

    /** 从 Milvus 检索指定文档的核心语义块 (生成任务不用 minScore 过滤, 直接取 top-K) */
    protected List<EmbeddingMatch<TextSegment>> retrieveTopChunks(String docUuid, int topK, String semanticQuery) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(semanticQuery).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(0.0) // 生成任务不过滤, 确保能检索到文档内容
                    .filter(new IsEqualTo("document_id", docUuid))
                    .build();
            return embeddingStore.search(request).matches();
        } catch (Exception e) {
            log.error("Milvus 检索失败: docUuid={}, error={}", docUuid, e.getMessage(), e);
            return List.of();
        }
    }

    /** 拼装检索上下文 */
    protected String buildContext(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            sb.append("【片段").append(i + 1).append("】\n");
            sb.append(matches.get(i).embedded().text()).append("\n\n");
        }
        return sb.toString();
    }

    /** 从 LLM 响应中提取 JSON 数组 */
    protected String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw;
    }

    /** 从 LLM 响应中提取 JSON 对象 (支持 markdown 代码块) */
    protected String extractJsonObject(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        // 去除 markdown 代码块
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return null;
    }

    /** 截断过长文本以适配数据库字段 */
    protected String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen - 3) + "..." : text;
    }
}
