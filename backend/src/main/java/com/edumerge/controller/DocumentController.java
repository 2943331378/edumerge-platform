package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.entity.Document;
import com.edumerge.entity.Session;
import com.edumerge.mq.producer.EmbeddingProducer;
import com.edumerge.service.DocumentService;
import com.edumerge.service.SessionService;
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

    @Autowired
    public DocumentController(@Value("${app.document.upload-dir:./uploads}") String uploadDir,
                              EmbeddingProducer embeddingProducer,
                              DocumentService documentService,
                              SessionService sessionService) {
        this.uploadDir = uploadDir;
        this.embeddingProducer = embeddingProducer;
        this.documentService = documentService;
        this.sessionService = sessionService;
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
                    .userId(1L)
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
            Session session = sessionService.create(1L, doc.getId(), doc.getTitle());

            boolean sent = embeddingProducer.sendEmbeddingTask(uuid, filePath.toString(), originalName, 1L);

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
}
