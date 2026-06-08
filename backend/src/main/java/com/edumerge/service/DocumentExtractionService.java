package com.edumerge.service;

import com.edumerge.ai.SubjectClassifier;
import com.edumerge.mq.listener.DocumentTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文档文本提取服务 — 封装文本提取 + 学科分类，供 DocumentListener 调用
 */
@Slf4j
@Service
public class DocumentExtractionService {

    private final DocumentTextExtractor textExtractor;
    private final SubjectClassifier subjectClassifier;
    private final DocumentService documentService;

    public DocumentExtractionService(DocumentTextExtractor textExtractor,
                                     SubjectClassifier subjectClassifier,
                                     DocumentService documentService) {
        this.textExtractor = textExtractor;
        this.subjectClassifier = subjectClassifier;
        this.documentService = documentService;
    }

    /**
     * 提取文档文本并进行学科分类
     *
     * @param documentPath 文档文件路径
     * @param documentId   Milvus 文档 UUID（用于日志）
     * @param dbDocId      MySQL 文档主键（用于持久化学科分类）
     * @return 提取结果（文本 + 页数）
     * @throws IOException 文件读取或解析失败
     */
    public DocumentTextExtractor.ExtractionResult extract(Path documentPath, String documentId, Long dbDocId)
            throws IOException {
        // 1. 提取文本
        long stepStart = System.currentTimeMillis();
        DocumentTextExtractor.ExtractionResult result = textExtractor.extract(documentPath);
        log.info("文本提取完成: documentId={}, 长度={} 字符, 页数={}, 耗时={}ms",
                documentId, result.text().length(), result.pageCount(), System.currentTimeMillis() - stepStart);

        // 2. 学科分类（失败不阻塞主流程）
        if (dbDocId != null) {
            try {
                String subjectType = subjectClassifier.classify(result.text());
                documentService.updateSubjectType(dbDocId, subjectType);
                log.info("学科分类完成: docId={}, subjectType={}", dbDocId, subjectType);
            } catch (Exception e) {
                log.warn("学科分类失败(不影响主流程): {}", e.getMessage());
            }
        }

        return result;
    }
}
