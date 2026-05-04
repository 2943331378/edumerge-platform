package com.edumerge.mq.listener;

import com.edumerge.config.RabbitMQConfig;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档向量化消费者 — 监听向量化队列, 执行 PDF 解析 → 文本切块 → 向量化 → 存入 Milvus 全流程
 */
@Slf4j
@Component
public class DocumentListener {

    private final EmbeddingModel embeddingModel;
    private final MilvusEmbeddingStore embeddingStore;
    private final DocumentService documentService;

    @Autowired
    public DocumentListener(EmbeddingModel embeddingModel,
                            MilvusEmbeddingStore embeddingStore,
                            DocumentService documentService) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentService = documentService;
    }

    @RabbitListener(queues = RabbitMQConfig.EMBEDDING_QUEUE)
    public void handleEmbeddingTask(DocumentProcessMessage message) {
        String documentId = message.getDocumentId();
        String filePath = message.getFilePath();
        log.info("收到向量化任务: documentId={}, filePath={}", documentId, filePath);

        Path pdfPath = Path.of(filePath);

        // 1. 校验文件存在
        if (!Files.exists(pdfPath)) {
            log.error("向量化失败: 文件不存在: {}", filePath);
            return;
        }

        try {
            // 2. PDF 提取文本
            String text = extractPdfText(pdfPath);
            if (text.isBlank()) {
                log.warn("PDF 文本为空: documentId={}", documentId);
                return;
            }
            log.info("PDF 文本提取完成: documentId={}, 长度={} 字符", documentId, text.length());

            // 3. 使用递归切分器切块 (chunkSize=500, overlap=50)
            DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
            Document langDoc = Document.from(text);
            List<TextSegment> segments = splitter.split(langDoc);
            log.info("文本切块完成: documentId={}, 块数={}", documentId, segments.size());

            if (segments.isEmpty()) {
                log.warn("文本切块后无内容: documentId={}", documentId);
                return;
            }

            // 4. 为每个块附加元数据 (document_id + chunk_index)
            List<TextSegment> enrichedSegments = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                Metadata meta = new Metadata()
                        .put("document_id", documentId)
                        .put("chunk_index", i);
                enrichedSegments.add(TextSegment.from(segments.get(i).text(), meta));
            }

            // 5. 批量向量化
            log.info("开始批量向量化: documentId={}, 块数={}", documentId, enrichedSegments.size());
            List<Embedding> embeddings = new ArrayList<>();
            for (TextSegment segment : enrichedSegments) {
                Response<Embedding> response = embeddingModel.embed(segment);
                embeddings.add(response.content());
            }
            log.info("向量化完成: documentId={}, 向量数={}", documentId, embeddings.size());

            // 6. 存入 Milvus
            embeddingStore.addAll(embeddings, enrichedSegments);
            log.info("向量存储完成: documentId={}, 块数={}", documentId, enrichedSegments.size());

            // 7. 更新 MySQL 文档状态为 COMPLETED
            updateDocStatus(filePath, "COMPLETED", enrichedSegments.size(), embeddings.size(), null);

        } catch (IOException e) {
            log.error("PDF 解析失败: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        } catch (Exception e) {
            log.error("向量化流程异常: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        }
    }

    /**
     * 按文件路径查找文档并更新处理状态 (委托 Service 层)
     */
    private void updateDocStatus(String filePath, String status, Integer chunkCount, Integer vectorCount, String statusMessage) {
        try {
            com.edumerge.entity.Document doc = documentService.getByFilePath(filePath);
            if (doc == null) {
                log.warn("未找到文档记录: filePath={}", filePath);
                return;
            }
            documentService.updateStatus(doc.getId(), status, chunkCount, vectorCount, statusMessage);
            log.info("文档状态更新: id={}, status={}", doc.getId(), status);
        } catch (Exception e) {
            log.error("更新文档状态失败: filePath={}, error={}", filePath, e.getMessage());
        }
    }

    /**
     * 使用 Apache PDFBox 提取 PDF 全文
     */
    private String extractPdfText(Path pdfPath) throws IOException {
        try (PDDocument pdfDoc = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(pdfDoc);
        }
    }
}
