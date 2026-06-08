package com.edumerge.mq.listener;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edumerge.ai.AiOutlineGenerator;
import com.edumerge.ai.SubjectClassifier;
import com.edumerge.config.RabbitMQConfig;
import com.edumerge.entity.DocumentChunk;
import com.edumerge.mapper.DocumentChunkMapper;
import com.edumerge.mq.message.DocumentProcessMessage;
import com.edumerge.service.DocumentService;
import com.edumerge.store.MilvusEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 文档向量化消费者 — 监听向量化队列, 执行文档解析 → 文本切块 → 向量化 → 存入 Milvus 全流程
 */
@Slf4j
@Component
public class DocumentListener {

    private final EmbeddingModel embeddingModel;
    private final MilvusEmbeddingStore embeddingStore;
    private final DocumentService documentService;
    private final DocumentChunkMapper documentChunkMapper;
    private final AiOutlineGenerator outlineGenerator;
    private final SubjectClassifier subjectClassifier;
    private final DocumentTextExtractor textExtractor;
    private final ExecutorService documentTaskExecutor;

    @Value("${app.rag.chunk-size:1000}")
    private int chunkSize;
    @Value("${app.rag.chunk-overlap:100}")
    private int chunkOverlap;

    public DocumentListener(EmbeddingModel embeddingModel,
                            MilvusEmbeddingStore embeddingStore,
                            DocumentService documentService,
                            DocumentChunkMapper documentChunkMapper,
                            AiOutlineGenerator outlineGenerator,
                            SubjectClassifier subjectClassifier,
                            DocumentTextExtractor textExtractor,
                            @Qualifier("documentTaskExecutor") ExecutorService documentTaskExecutor) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentService = documentService;
        this.documentChunkMapper = documentChunkMapper;
        this.outlineGenerator = outlineGenerator;
        this.subjectClassifier = subjectClassifier;
        this.textExtractor = textExtractor;
        this.documentTaskExecutor = documentTaskExecutor;
    }

    @RabbitListener(queues = RabbitMQConfig.EMBEDDING_QUEUE)
    public void handleEmbeddingTask(DocumentProcessMessage message) {
        String documentId = message.getDocumentId();
        String filePath = message.getFilePath();
        log.info("收到向量化任务: documentId={}, filePath={}", documentId, filePath);

        Path documentPath = Path.of(filePath);

        // 幂等检查: 文件已删除或文档已完成, 跳过
        if (!Files.exists(documentPath)) {
            log.info("文件已删除, 跳过: {}", filePath);
            return;
        }
        com.edumerge.entity.Document existing = documentService.getByFilePath(filePath);
        if (existing != null && "COMPLETED".equals(existing.getStatus())) {
            log.info("文档已完成向量化, 跳过: id={}", existing.getId());
            return;
        }

        try {
            long pipelineStart = System.currentTimeMillis();
            updateDocStatus(filePath, "PROCESSING", null, null, null);

            // 1. 提取文本
            long stepStart = System.currentTimeMillis();
            DocumentTextExtractor.ExtractionResult result = textExtractor.extract(documentPath);
            if (result.text().isBlank()) {
                log.warn("文档文本为空: documentId={}", documentId);
                updateDocStatus(filePath, "FAILED", null, null, "无法提取文本(可能为加密、图片型文档或不支持的文件格式)");
                return;
            }
            String text = result.text();
            log.info("文本提取完成: documentId={}, 长度={} 字符, 页数={}, 耗时={}ms",
                    documentId, text.length(), result.pageCount(), System.currentTimeMillis() - stepStart);

            // 2. 学科分类
            com.edumerge.entity.Document docForClassify = documentService.getByFilePath(filePath);
            if (docForClassify != null) {
                try {
                    String subjectType = subjectClassifier.classify(text);
                    documentService.updateSubjectType(docForClassify.getId(), subjectType);
                    log.info("学科分类完成: docId={}, subjectType={}", docForClassify.getId(), subjectType);
                } catch (Exception e) {
                    log.warn("学科分类失败(不影响主流程): {}", e.getMessage());
                }
            }

            // 3. 切块 — PPT 按幻灯片边界切分，其他格式递归切分
            stepStart = System.currentTimeMillis();
            String extension = textExtractor.getExtension(documentPath);
            List<TextSegment> segments;
            if (extension.equals("ppt") || extension.equals("pptx")) {
                segments = splitBySlide(text);
            } else {
                DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
                segments = splitter.split(Document.from(text));
            }
            log.info("文本切块完成: documentId={}, 块数={}, 耗时={}ms", documentId, segments.size(), System.currentTimeMillis() - stepStart);

            if (segments.isEmpty()) {
                log.warn("文本切块后无内容: documentId={}", documentId);
                updateDocStatus(filePath, "FAILED", null, null, "文本切块后无有效内容");
                return;
            }

            // 4. 批量保存切块到 MySQL
            stepStart = System.currentTimeMillis();
            com.edumerge.entity.Document doc = documentService.getByFilePath(filePath);
            if (doc != null) {
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
                if (result.pageCount() > 0) {
                    documentService.updatePageCount(doc.getId(), result.pageCount());
                }
                log.info("文档切块批量入库: docId={}, 块数={}, 耗时={}ms", doc.getId(), chunkBatch.size(), System.currentTimeMillis() - stepStart);
            }

            // 5. 附加元数据 (document_id + chunk_index)
            List<TextSegment> enrichedSegments = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                Metadata meta = new Metadata()
                        .put("document_id", documentId)
                        .put("chunk_index", i);
                enrichedSegments.add(TextSegment.from(segments.get(i).text(), meta));
            }

            // 6. 异步触发大纲生成
            if (doc != null) {
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
            }

            // 7. 并发向量化
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

            // 8. 存入 Milvus
            stepStart = System.currentTimeMillis();
            embeddingStore.addAll(embeddings, enrichedSegments);
            log.info("向量存储完成: documentId={}, 块数={}, 耗时={}ms", documentId, enrichedSegments.size(), System.currentTimeMillis() - stepStart);

            // 9. 批量更新切块状态
            if (doc != null) {
                documentChunkMapper.update(null,
                        new LambdaUpdateWrapper<DocumentChunk>()
                                .eq(DocumentChunk::getDocumentId, doc.getId())
                                .set(DocumentChunk::getEmbeddingStatus, "COMPLETED"));
            }

            // 10. 更新文档状态
            updateDocStatus(filePath, "COMPLETED", enrichedSegments.size(), embeddings.size(), null);
            log.info("文档处理全流程完成: documentId={}, 总块数={}, 总耗时={}ms",
                    documentId, enrichedSegments.size(), System.currentTimeMillis() - pipelineStart);

        } catch (IOException e) {
            log.error("文档解析失败: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        } catch (Exception e) {
            log.error("向量化流程异常: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        }
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
                segments.addAll(fallback.split(Document.from(slide)));
            }
        }
        return segments;
    }

    private void updateDocStatus(String filePath, String status, Integer chunkCount, Integer vectorCount, String statusMessage) {
        try {
            com.edumerge.entity.Document doc = documentService.getByFilePath(filePath);
            if (doc == null) {
                log.warn("未找到文档记录: filePath={}", filePath);
                return;
            }
            documentService.updateStatus(doc.getId(), status, chunkCount, vectorCount, statusMessage);
        } catch (Exception e) {
            log.error("更新文档状态失败: filePath={}, error={}", filePath, e.getMessage());
        }
    }
}
