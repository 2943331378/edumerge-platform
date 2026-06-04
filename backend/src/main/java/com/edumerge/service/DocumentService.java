package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.ai.AiOutlineGenerator;
import com.edumerge.dto.OutlineResponse;
import com.edumerge.entity.Document;
import com.edumerge.entity.DocumentChunk;
import com.edumerge.entity.DocumentOutline;
import com.edumerge.entity.Session;
import com.edumerge.mapper.DocumentChunkMapper;
import com.edumerge.mapper.DocumentMapper;
import com.edumerge.mapper.SessionMapper;
import com.edumerge.mq.producer.EmbeddingProducer;
import com.edumerge.security.SecurityUtils;
import com.edumerge.store.MilvusEmbeddingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final SessionMapper sessionMapper;
    private final MilvusEmbeddingStore milvusEmbeddingStore;
    private final DocumentOutlineService outlineService;
    private final SessionService sessionService;
    private final EmbeddingProducer embeddingProducer;
    private final AiOutlineGenerator outlineGenerator;
    private final ObjectMapper objectMapper;
    private final String uploadDir;

    @Autowired
    public DocumentService(DocumentMapper documentMapper, DocumentChunkMapper documentChunkMapper,
                           SessionMapper sessionMapper, MilvusEmbeddingStore milvusEmbeddingStore,
                           DocumentOutlineService outlineService, SessionService sessionService,
                           EmbeddingProducer embeddingProducer, AiOutlineGenerator outlineGenerator,
                           ObjectMapper objectMapper,
                           @Value("${app.document.upload-dir:./uploads}") String uploadDir) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.sessionMapper = sessionMapper;
        this.milvusEmbeddingStore = milvusEmbeddingStore;
        this.outlineService = outlineService;
        this.sessionService = sessionService;
        this.embeddingProducer = embeddingProducer;
        this.outlineGenerator = outlineGenerator;
        this.objectMapper = objectMapper;
        this.uploadDir = uploadDir;
    }

    // ═══════ CRUD ═══════

    @Transactional
    public Document create(Document doc) {
        documentMapper.insert(doc);
        log.info("文档记录已创建: id={}, fileName={}", doc.getId(), doc.getFileName());
        return doc;
    }

    @Transactional(readOnly = true)
    public List<Document> listByUserId(Long userId) {
        return documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId)
                        .eq(Document::getStatus, "COMPLETED")
                        .orderByDesc(Document::getCreatedAt));
    }

    @Transactional(readOnly = true)
    public List<Document> listRecent() {
        return documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .orderByDesc(Document::getCreatedAt)
                        .last("LIMIT 50"));
    }

    @Transactional(readOnly = true)
    public Document getById(Long id) {
        return documentMapper.selectById(id);
    }

    @Transactional(readOnly = true)
    public Document getByFilePath(String filePath) {
        return documentMapper.selectOne(
                new LambdaQueryWrapper<Document>().eq(Document::getFilePath, filePath));
    }

    @Transactional(readOnly = true)
    public List<DocumentChunk> listChunks(Long docId) {
        return documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, docId)
                        .orderByAsc(DocumentChunk::getChunkIndex));
    }

    @Transactional
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

    @Transactional
    public void delete(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }

        // 1. 删除关联会话
        sessionMapper.delete(new LambdaQueryWrapper<Session>()
                .eq(Session::getDocId, id));

        // 1.5 删除文档大纲
        outlineService.deleteByDocId(id);

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

    // ═══════ 文档上传 ═══════

    /**
     * 上传文档：保存文件 → 创建文档记录 → 创建会话 → 发送向量化任务
     *
     * @return 包含 documentId, sessionId, fileName, size, status 的 Map
     * @throws IllegalArgumentException 文件校验失败
     * @throws IOException              文件保存失败
     */
    @Transactional
    public Map<String, Object> upload(String originalName, long fileSize, java.io.InputStream inputStream) {
        String extension = getSupportedExtension(originalName);
        if (extension == null) {
            throw new IllegalArgumentException("仅支持 PDF、Word、PPT、TXT 文件");
        }

        String uuid = UUID.randomUUID().toString().replace("-", "");
        Path uploadPath = Path.of(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath);
        } catch (IOException e) {
            throw new RuntimeException("创建上传目录失败: " + e.getMessage(), e);
        }

        Path filePath = uploadPath.resolve(uuid + "." + extension);
        try {
            Files.copy(inputStream, filePath);
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }

        log.info("文件上传成功: uuid={}, name={}, size={}", uuid, originalName, fileSize);

        Long userId = SecurityUtils.getCurrentUserId();

        Document doc = Document.builder()
                .userId(userId)
                .documentId(uuid)
                .title(originalName.replaceAll("(?i)\\.[^.]+$", ""))
                .fileName(originalName)
                .fileSize(fileSize)
                .fileType(extension)
                .filePath(filePath.toString())
                .status("UPLOADING")
                .build();
        create(doc);

        Session session = sessionService.create(userId, doc.getId(), doc.getTitle());
        boolean sent = embeddingProducer.sendEmbeddingTask(uuid, filePath.toString(), originalName, userId);

        return Map.of(
                "documentId", uuid,
                "sessionId", session.getId(),
                "fileName", originalName,
                "size", fileSize,
                "status", sent ? "processing" : "pending"
        );
    }

    private String getSupportedExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "pdf", "doc", "docx", "ppt", "pptx", "txt" -> extension;
            default -> null;
        };
    }

    // ═══════ 文档大纲 ═══════

    /** 获取文档大纲（解析后的响应格式） */
    @Transactional(readOnly = true)
    public OutlineResponse getOutline(Long docId) {
        Document doc = getById(docId);
        if (doc == null) throw new IllegalArgumentException("文档不存在");

        DocumentOutline outline = outlineService.getByDocId(docId);
        if (outline == null) throw new IllegalArgumentException("文档大纲尚未生成");
        return OutlineResponse.from(outline, objectMapper);
    }

    /** 更新文档大纲 */
    @Transactional
    public OutlineResponse updateOutline(Long docId, String body) {
        Document doc = getById(docId);
        if (doc == null) throw new IllegalArgumentException("文档不存在");

        // 校验 JSON 合法性
        try {
            objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("大纲 JSON 格式无效: " + e.getMessage());
        }

        DocumentOutline updated = outlineService.update(docId, body, SecurityUtils.getCurrentUserId());
        return OutlineResponse.from(updated, objectMapper);
    }

    /**
     * 重新生成文档大纲 — 先生成成功后再删除旧大纲，避免生成失败时数据丢失
     */
    @Transactional
    public OutlineResponse regenerateOutline(Long docId) {
        Document doc = getById(docId);
        if (doc == null) throw new IllegalArgumentException("文档不存在");
        if (!"COMPLETED".equals(doc.getStatus())) throw new IllegalArgumentException("文档尚未处理完成");
        if (doc.getChunkCount() == null || doc.getChunkCount() == 0) throw new IllegalArgumentException("文档无切块数据");

        DocumentOutline newOutline = outlineGenerator.generateAndSave(
                docId, SecurityUtils.getCurrentUserId(), doc.getChunkCount());
        if (newOutline == null) throw new IllegalStateException("大纲生成失败, 请稍后重试");

        // 生成成功后，删除旧版本的大纲（保留最新版本）
        outlineService.deleteOldVersions(docId, newOutline.getVersion());

        return OutlineResponse.from(newOutline, objectMapper);
    }
}
