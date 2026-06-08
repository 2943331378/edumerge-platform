package com.edumerge.mq.listener;

import com.edumerge.config.RabbitMQConfig;
import com.edumerge.mq.message.DocumentProcessMessage;
import com.edumerge.service.DocumentExtractionService;
import com.edumerge.service.DocumentService;
import com.edumerge.service.EmbeddingPipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档向量化消费者 — 监听向量化队列，协调提取、切块、向量化全流程
 */
@Slf4j
@Component
public class DocumentListener {

    private final DocumentService documentService;
    private final DocumentExtractionService extractionService;
    private final EmbeddingPipelineService pipelineService;

    public DocumentListener(DocumentService documentService,
                            DocumentExtractionService extractionService,
                            EmbeddingPipelineService pipelineService) {
        this.documentService = documentService;
        this.extractionService = extractionService;
        this.pipelineService = pipelineService;
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

            // 1. 提取文本 + 学科分类
            com.edumerge.entity.Document docForExtract = documentService.getByFilePath(filePath);
            Long dbDocId = docForExtract != null ? docForExtract.getId() : null;
            DocumentTextExtractor.ExtractionResult result = extractionService.extract(documentPath, documentId, dbDocId);
            if (result.text().isBlank()) {
                log.warn("文档文本为空: documentId={}", documentId);
                updateDocStatus(filePath, "FAILED", null, null, "无法提取文本(可能为加密、图片型文档或不支持的文件格式)");
                return;
            }

            // 2. 切块 + 向量化 + 存储
            com.edumerge.entity.Document doc = documentService.getByFilePath(filePath);
            if (doc == null) {
                log.error("文档记录不存在: filePath={}", filePath);
                updateDocStatus(filePath, "FAILED", null, null, "文档记录不存在");
                return;
            }
            int chunkCount = pipelineService.processAndEmbed(doc, documentId, result.text(), filePath, result.pageCount());

            // 3. 更新文档状态
            updateDocStatus(filePath, "COMPLETED", chunkCount, chunkCount, null);
            log.info("文档处理全流程完成: documentId={}, 总块数={}, 总耗时={}ms",
                    documentId, chunkCount, System.currentTimeMillis() - pipelineStart);

        } catch (IOException e) {
            log.error("文档解析失败: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        } catch (Exception e) {
            log.error("向量化流程异常: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocStatus(filePath, "FAILED", null, null, e.getMessage());
        }
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
