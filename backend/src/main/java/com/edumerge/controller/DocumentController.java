package com.edumerge.controller;

import com.edumerge.ai.AiOutlineGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.dto.OutlineResponse;
import com.edumerge.entity.Document;
import com.edumerge.entity.DocumentOutline;
import com.edumerge.entity.Session;
import com.edumerge.mq.producer.EmbeddingProducer;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.DocumentOutlineService;
import com.edumerge.service.DocumentService;
import com.edumerge.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档上传与查询接口 — 业务逻辑委托给 DocumentService
 */
@Slf4j
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final String uploadDir;
    private final EmbeddingProducer embeddingProducer;
    private final DocumentService documentService;
    private final SessionService sessionService;
    private final DocumentOutlineService outlineService;
    private final AiOutlineGenerator outlineGenerator;
    private final ObjectMapper objectMapper;

    @Autowired
    public DocumentController(@Value("${app.document.upload-dir:./uploads}") String uploadDir,
                              EmbeddingProducer embeddingProducer,
                              DocumentService documentService,
                              SessionService sessionService,
                              DocumentOutlineService outlineService,
                              AiOutlineGenerator outlineGenerator,
                              ObjectMapper objectMapper) {
        this.uploadDir = uploadDir;
        this.embeddingProducer = embeddingProducer;
        this.documentService = documentService;
        this.sessionService = sessionService;
        this.outlineService = outlineService;
        this.outlineGenerator = outlineGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传学习资料
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.fail("文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        String extension = getSupportedExtension(originalName);
        if (extension == null) {
            return Result.fail("仅支持 PDF、Word、PPT、TXT 文件");
        }

        try {
            String uuid = UUID.randomUUID().toString().replace("-", "");
            Path uploadPath = Path.of(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(uploadPath);

            Path filePath = uploadPath.resolve(uuid + "." + extension);
            file.transferTo(filePath.toFile());

            log.info("文件上传成功: uuid={}, name={}, size={}", uuid, originalName, file.getSize());

            // 委托 Service 层处理业务逻辑
            Document doc = Document.builder()
                    .userId(SecurityUtils.getCurrentUserId())
                    .documentId(uuid)
                    .title(originalName.replaceAll("(?i)\\.[^.]+$", ""))
                    .fileName(originalName)
                    .fileSize(file.getSize())
                    .fileType(extension)
                    .filePath(filePath.toString())
                    .status("UPLOADING")
                    .build();
            documentService.create(doc);

            // 上传时同步创建会话, 用户可立即进入
            Long userId = SecurityUtils.getCurrentUserId();
            Session session = sessionService.create(userId, doc.getId(), doc.getTitle());

            boolean sent = embeddingProducer.sendEmbeddingTask(uuid, filePath.toString(), originalName, userId);

            return Result.success(sent ? "上传成功，正在异步处理" : "上传成功，消息队列不可用，稍后重试",
                    Map.of(
                        "documentId", uuid,
                        "sessionId", session.getId(),
                        "fileName", originalName,
                        "size", file.getSize(),
                        "status", sent ? "processing" : "pending"
                    ));

        } catch (IOException e) {
            log.error("文件保存失败: {}", e.getMessage(), e);
            return Result.fail("文件保存失败: " + e.getMessage());
        }
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

    /**
     * 查询文档列表
     */
    @GetMapping
    public Result<List<Document>> list() {
        return Result.success(documentService.listRecent());
    }

    /**
     * 查询文档的所有切片 — 用于数据要素评测集 (Golden Dataset) 构建
     * 将非结构化数据以切片粒度暴露给评测脚本，支持精确溯源
     */
    @GetMapping("/{docId}/chunks")
    public Result<List<com.edumerge.entity.DocumentChunk>> chunks(@PathVariable Long docId) {
        Document doc = documentService.getById(docId);
        if (doc == null) {
            return Result.fail("文档不存在");
        }
        return Result.success(documentService.listChunks(docId));
    }

    /**
     * 删除文档及其关联的全部数据（文件、向量、切片、会话）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            documentService.delete(id);
            return Result.success("文档已删除", null);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            log.error("文档删除失败: id={}", id, e);
            return Result.fail("文档删除失败: " + e.getMessage());
        }
    }

    // ═══════ 文档大纲 API ═══════

    /**
     * 获取文档大纲 — 返回解析后的 OutlineResponse (outline 字段为 JSON 对象而非字符串)
     */
    @GetMapping("/{docId}/outline")
    public Result<OutlineResponse> getOutline(@PathVariable Long docId) {
        Document doc = documentService.getById(docId);
        if (doc == null) return Result.fail("文档不存在");

        DocumentOutline outline = outlineService.getByDocId(docId);
        if (outline == null) return Result.fail("文档大纲尚未生成");
        return Result.success(OutlineResponse.from(outline, objectMapper));
    }

    /**
     * 更新文档大纲 (用户编辑后保存)
     * 请求体为 OutlineData JSON 对象，后程序列化为字符串存储
     */
    @PutMapping("/{docId}/outline")
    public Result<OutlineResponse> updateOutline(@PathVariable Long docId, @RequestBody String body) {
        Document doc = documentService.getById(docId);
        if (doc == null) return Result.fail("文档不存在");

        // 校验 JSON 合法性
        try {
            objectMapper.readTree(body);
        } catch (Exception e) {
            return Result.fail("大纲 JSON 格式无效: " + e.getMessage());
        }

        try {
            DocumentOutline updated = outlineService.update(docId, body, SecurityUtils.getCurrentUserId());
            return Result.success("大纲已保存", OutlineResponse.from(updated, objectMapper));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 重新生成文档大纲 — 先生成成功后再删除旧大纲，避免生成失败时数据丢失
     */
    @PostMapping("/{docId}/outline/regenerate")
    public Result<OutlineResponse> regenerateOutline(@PathVariable Long docId) {
        Document doc = documentService.getById(docId);
        if (doc == null) return Result.fail("文档不存在");
        if (!"COMPLETED".equals(doc.getStatus())) return Result.fail("文档尚未处理完成");
        if (doc.getChunkCount() == null || doc.getChunkCount() == 0) return Result.fail("文档无切块数据");

        try {
            // 先生成新大纲（旧大纲仍在数据库中）
            DocumentOutline newOutline = outlineGenerator.generateAndSave(
                    docId, SecurityUtils.getCurrentUserId(), doc.getChunkCount());
            if (newOutline == null) return Result.fail("大纲生成失败, 请稍后重试");

            // 生成成功后，删除旧版本的大纲（保留最新版本）
            outlineService.deleteOldVersions(docId, newOutline.getVersion());

            return Result.success("大纲已重新生成", OutlineResponse.from(newOutline, objectMapper));
        } catch (Exception e) {
            log.error("大纲重新生成失败: docId={}", docId, e);
            return Result.fail("大纲生成失败: " + e.getMessage());
        }
    }
}
