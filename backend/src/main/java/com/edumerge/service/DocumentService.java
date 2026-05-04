package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.Document;
import com.edumerge.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class DocumentService {

    private final DocumentMapper documentMapper;

    @Autowired
    public DocumentService(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    /** 创建文档记录 */
    public Document create(Document doc) {
        documentMapper.insert(doc);
        log.info("文档记录已创建: id={}, fileName={}", doc.getId(), doc.getFileName());
        return doc;
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
}
