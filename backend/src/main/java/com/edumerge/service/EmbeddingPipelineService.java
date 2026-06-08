package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edumerge.ai.AiOutlineGenerator;
import com.edumerge.entity.Document;
import com.edumerge.entity.DocumentChunk;
import com.edumerge.mapper.DocumentChunkMapper;
import com.edumerge.mq.listener.DocumentTextExtractor;
import com.edumerge.store.MilvusEmbeddingStore;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 向量化管线服务 — 封装文本切块、MySQL 元数据入库、向量化、Milvus 存储全流程
 */
@Slf4j
@Service
public class EmbeddingPipelineService {

    private final EmbeddingModel embeddingModel;
    private final MilvusEmbeddingStore embeddingStore;
    private final DocumentService documentService;
    private final DocumentChunkMapper documentChunkMapper;
    private final AiOutlineGenerator outlineGenerator;
    private final DocumentTextExtractor textExtractor;
    private final ExecutorService documentTaskExecutor;

    @Value("${app.rag.chunk-size:1000}")
    private int chunkSize;
    @Value("${app.rag.chunk-overlap:100}")
    private int chunkOverlap;

    public EmbeddingPipelineService(EmbeddingModel embeddingModel,
                                    MilvusEmbeddingStore embeddingStore,
                                    DocumentService documentService,
                                    DocumentChunkMapper documentChunkMapper,
                                    AiOutlineGenerator outlineGenerator,
                                    DocumentTextExtractor textExtractor,
                                    @Qualifier("documentTaskExecutor") ExecutorService documentTaskExecutor) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentService = documentService;
        this.documentChunkMapper = documentChunkMapper;
        this.outlineGenerator = outlineGenerator;
        this.textExtractor = textExtractor;
        this.documentTaskExecutor = documentTaskExecutor;
    }

    /**
     * 切块 + 元数据入库 + 向量化 + Milvus 存储
     *
     * @param doc        MySQL 文档实体（已持久化）
     * @param documentId Milvus 文档 UUID
     * @param text       已提取的文本内容
     * @param filePath   文件路径（用于日志和按扩展名判断切分策略）
     * @param pageCount  提取的页数/幻灯片数
     * @return 向量化的切块数量
     */
    public int processAndEmbed(Document doc, String documentId, String text, String filePath, int pageCount) {
        long pipelineStart = System.currentTimeMillis();

        // 1. 切块 — PPT 按幻灯片边界切分，其他格式递归切分
        long stepStart = System.currentTimeMillis();
        Path documentPath = Path.of(filePath);
        String extension = textExtractor.getExtension(documentPath);
        List<TextSegment> segments;
        if (extension.equals("ppt") || extension.equals("pptx")) {
            segments = splitBySlide(text);
        } else {
            DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
            segments = splitter.split(dev.langchain4j.data.document.Document.from(text));
        }
        log.info("文本切块完成: documentId={}, 块数={}, 耗时={}ms",
                documentId, segments.size(), System.currentTimeMillis() - stepStart);

        if (segments.isEmpty()) {
            throw new IllegalStateException("文本切块后无有效内容");
        }

        // 2. 批量保存切块到 MySQL
        stepStart = System.currentTimeMillis();
        List<DocumentChunk> chunkBatch = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            chunkBatch.add(DocumentChunk.builder()
                    .documentId(doc.getId())
                    .chunkIndex(i)
                    .content(segments.get(i).text())
                    .embeddingStatus("PENDING")
                    .build());
        }
        documentChunkMapper.insertBatch(chunkBatch);
        if (pageCount > 0) {
            documentService.updatePageCount(doc.getId(), pageCount);
        }
        log.info("文档切块批量入库: docId={}, 块数={}, 耗时={}ms",
                doc.getId(), chunkBatch.size(), System.currentTimeMillis() - stepStart);

        // 3. 附加元数据 (document_id + chunk_index)
        List<TextSegment> enrichedSegments = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Metadata meta = new Metadata()
                    .put("document_id", documentId)
                    .put("chunk_index", i);
            enrichedSegments.add(TextSegment.from(segments.get(i).text(), meta));
        }

        // 4. 异步触发大纲生成
        final Long outlineDocId = doc.getId();
        final Long outlineUserId = doc.getUserId();
        final int outlineChunkCount = enrichedSegments.size();
        CompletableFuture.runAsync(() -> {
            try {
                outlineGenerator.generateAndSave(outlineDocId, outlineUserId, outlineChunkCount);
                log.info("文档大纲生成完成: docId={}", outlineDocId);
            } catch (Exception e) {
                log.warn("文档大纲生成失败(不影响主流程): docId={}, error={}", outlineDocId, e.getMessage());
            }
        }, documentTaskExecutor);

        // 5. 并发向量化
        long embedStart = System.currentTimeMillis();
        int totalChunks = enrichedSegments.size();
        int batchSize = 10;
        int batchCount = (totalChunks + batchSize - 1) / batchSize;

        @SuppressWarnings("unchecked")
        CompletableFuture<Response<List<Embedding>>>[] futures = new CompletableFuture[batchCount];
        for (int b = 0; b < batchCount; b++) {
            final int offset = b * batchSize;
            final int end = Math.min(offset + batchSize, totalChunks);
            final List<TextSegment> batch = enrichedSegments.subList(offset, end);
            futures[b] = CompletableFuture.supplyAsync(() -> embeddingModel.embedAll(batch), documentTaskExecutor);
        }

        List<Embedding> embeddings = new ArrayList<>(totalChunks);
        for (int b = 0; b < batchCount; b++) {
            Response<List<Embedding>> resp = futures[b].join();
            embeddings.addAll(resp.content());
            log.info("向量化进度: {}/{} 块", Math.min((b + 1) * batchSize, totalChunks), totalChunks);
        }
        log.info("向量化完成: documentId={}, 向量数={}, 批次={}, 耗时={}ms",
                documentId, embeddings.size(), batchCount, System.currentTimeMillis() - embedStart);

        // 6. 存入 Milvus
        stepStart = System.currentTimeMillis();
        embeddingStore.addAll(embeddings, enrichedSegments);
        log.info("向量存储完成: documentId={}, 块数={}, 耗时={}ms",
                documentId, enrichedSegments.size(), System.currentTimeMillis() - stepStart);

        // 7. 批量更新切块状态
        documentChunkMapper.update(null,
                new LambdaUpdateWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, doc.getId())
                        .set(DocumentChunk::getEmbeddingStatus, "COMPLETED"));

        log.info("向量化管线完成: documentId={}, 总块数={}, 总耗时={}ms",
                documentId, enrichedSegments.size(), System.currentTimeMillis() - pipelineStart);

        return enrichedSegments.size();
    }

    /** 按幻灯片边界切分 PPT 文本 */
    private List<TextSegment> splitBySlide(String text) {
        String[] slides = text.split("(?=【幻灯片\\d+】)");
        List<TextSegment> segments = new ArrayList<>();
        DocumentSplitter fallback = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        for (String slide : slides) {
            if (slide.isBlank()) continue;
            if (slide.length() <= chunkSize) {
                segments.add(TextSegment.from(slide.trim()));
            } else {
                segments.addAll(fallback.split(dev.langchain4j.data.document.Document.from(slide)));
            }
        }
        return segments;
    }
}
