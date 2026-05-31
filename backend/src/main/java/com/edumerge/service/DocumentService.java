package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.Document;
import com.edumerge.entity.DocumentChunk;
import com.edumerge.mapper.DocumentChunkMapper;
import com.edumerge.mapper.DocumentMapper;
import com.edumerge.mapper.SessionMapper;
import com.edumerge.store.MilvusEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final SessionMapper sessionMapper;
    private final MilvusEmbeddingStore milvusEmbeddingStore;

    @Autowired
    public DocumentService(DocumentMapper documentMapper, DocumentChunkMapper documentChunkMapper,
                           SessionMapper sessionMapper, MilvusEmbeddingStore milvusEmbeddingStore) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.sessionMapper = sessionMapper;
        this.milvusEmbeddingStore = milvusEmbeddingStore;
    }

    /** 创建文档记录 */
    public Document create(Document doc) {
        documentMapper.insert(doc);
        log.info("文档记录已创建: id={}, fileName={}", doc.getId(), doc.getFileName());
        return doc;
    }

    /** 查询用户已完成处理的文档 */
    public List<Document> listByUserId(Long userId) {
        return documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId)
                        .eq(Document::getStatus, "COMPLETED")
                        .orderByDesc(Document::getCreatedAt));
    }

    /** 查询文档列表 (最近 50 条) */
    public List<Document> listRecent() {
        return documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .orderByDesc(Document::getCreatedAt)
                        .last("LIMIT 50"));
    }

    /** 按 ID 查询 */
    public Document getById(Long id) {
        return documentMapper.selectById(id);
    }

    /** 按文件路径查询 */
    public Document getByFilePath(String filePath) {
        return documentMapper.selectOne(
                new LambdaQueryWrapper<Document>().eq(Document::getFilePath, filePath));
    }

    /**
     * 按文档 ID 查询所有切片 — 为数据要素评测集构建提供非结构化数据源
     */
    public List<DocumentChunk> listChunks(Long docId) {
        return documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, docId)
                        .orderByAsc(DocumentChunk::getChunkIndex));
    }

    /** 更新文档状态 */
    public void updateStatus(Long id, String status, Integer chunkCount, Integer vectorCount, String message) {
        Document doc = new Document();
        doc.setId(id);
        doc.setStatus(status);
        if (chunkCount != null) doc.setChunkCount(chunkCount);
        if (vectorCount != null) doc.setVectorCount(vectorCount);
        if (message != null) doc.setStatusMessage(message);
        documentMapper.updateById(doc);
        log.info("文档状态已更新: id={}, status={}", id, status);
    }

    /** 删除文档及其关联数据（文件、向量、切片、会话） */
    public void delete(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }

        // 1. 删除关联会话
        sessionMapper.delete(new LambdaQueryWrapper<com.edumerge.entity.Session>()
                .eq(com.edumerge.entity.Session::getDocId, id));

        // 2. 删除 Milvus 向量
        if (doc.getDocumentId() != null && !doc.getDocumentId().isBlank()) {
            try {
                milvusEmbeddingStore.deleteByDocumentId(doc.getDocumentId());
            } catch (Exception e) {
                log.warn("Milvus 向量删除失败（可能集合中无此文档数据）: {}", e.getMessage());
            }
        }

        // 3. 删除 MySQL 切片记录
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, id));

        // 4. 删除物理文件
        if (doc.getFilePath() != null) {
            try {
                Files.deleteIfExists(Path.of(doc.getFilePath()));
            } catch (IOException e) {
                log.warn("文件删除失败: {}", e.getMessage());
            }
        }

        // 5. 软删除文档记录
        documentMapper.deleteById(id);
        log.info("文档已删除: id={}, fileName={}", id, doc.getFileName());
    }
}
