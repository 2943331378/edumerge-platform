package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edumerge.ai.AiOutlineGenerator;
import com.edumerge.dto.OutlineResponse;
import com.edumerge.entity.*;
import com.edumerge.mapper.*;
import com.edumerge.mq.producer.EmbeddingProducer;
import com.edumerge.security.SecurityUtils;
import org.springframework.cache.annotation.CacheEvict;
import com.edumerge.common.util.FileMagicValidator;
import com.edumerge.store.MilvusEmbeddingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

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
    private final FlashcardMapper flashcardMapper;
    private final FlashcardReviewLogMapper flashcardReviewLogMapper;
    private final QuizMapper quizMapper;
    private final QuizAttemptMapper quizAttemptMapper;
    private final StudyNoteMapper studyNoteMapper;
    private final MindMapMapper mindMapMapper;
    private final FlowNoteMapper flowNoteMapper;
    private final CardDeckMapper cardDeckMapper;
    private final ConversationMapper conversationMapper;
    private final ChatHistoryMapper chatHistoryMapper;

    @Autowired
    public DocumentService(DocumentMapper documentMapper, DocumentChunkMapper documentChunkMapper,
                           SessionMapper sessionMapper, MilvusEmbeddingStore milvusEmbeddingStore,
                           DocumentOutlineService outlineService, SessionService sessionService,
                           EmbeddingProducer embeddingProducer, AiOutlineGenerator outlineGenerator,
                           ObjectMapper objectMapper,
                           @Value("${app.document.upload-dir:./uploads}") String uploadDir,
                           FlashcardMapper flashcardMapper, FlashcardReviewLogMapper flashcardReviewLogMapper,
                           QuizMapper quizMapper, QuizAttemptMapper quizAttemptMapper,
                           StudyNoteMapper studyNoteMapper, MindMapMapper mindMapMapper,
                           FlowNoteMapper flowNoteMapper, CardDeckMapper cardDeckMapper,
                           ConversationMapper conversationMapper, ChatHistoryMapper chatHistoryMapper) {
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
        this.flashcardMapper = flashcardMapper;
        this.flashcardReviewLogMapper = flashcardReviewLogMapper;
        this.quizMapper = quizMapper;
        this.quizAttemptMapper = quizAttemptMapper;
        this.studyNoteMapper = studyNoteMapper;
        this.mindMapMapper = mindMapMapper;
        this.flowNoteMapper = flowNoteMapper;
        this.cardDeckMapper = cardDeckMapper;
        this.conversationMapper = conversationMapper;
        this.chatHistoryMapper = chatHistoryMapper;
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
    public Document getById(Long id) {
        return documentMapper.selectById(id);
    }

    /** 校验文档归属当前用户，不归属则抛 403 */
    @Transactional(readOnly = true)
    public void verifyOwnership(Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Document doc = getById(docId);
        if (doc == null || !doc.getUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权访问此文档");
        }
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
    public void updatePageCount(Long id, int pageCount) {
        Document doc = new Document();
        doc.setId(id);
        doc.setPageCount(pageCount);
        documentMapper.updateById(doc);
    }

    @Transactional
    public void updateSubjectType(Long id, String subjectType) {
        Document doc = new Document();
        doc.setId(id);
        doc.setSubjectType(subjectType);
        documentMapper.updateById(doc);
        log.info("文档学科类型已更新: id={}, subjectType={}", id, subjectType);
    }

    @Transactional
    public void rename(Long id, String title) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) throw new IllegalArgumentException("文档不存在: " + id);
        Document update = new Document();
        update.setId(id);
        update.setTitle(title);
        update.setFileName(title);
        documentMapper.updateById(update);
        // 同步更新关联会话标题
        sessionMapper.update(null,
                new LambdaUpdateWrapper<Session>()
                        .eq(Session::getDocId, id)
                        .set(Session::getTitle, title));
        log.info("文档已重命名: id={}, newTitle={}", id, title);
    }

    @Transactional
    @CacheEvict(cacheNames = {"dashboard", "stats", "learningStats"}, allEntries = true)
    public void delete(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }

        // 1. 级联清理所有关联数据（子表优先）

        // 1.1 闪卡复习记录（依赖 flashcards）
        List<Flashcard> fCards = flashcardMapper.selectList(
                new LambdaQueryWrapper<Flashcard>().eq(Flashcard::getDocId, id));
        if (!fCards.isEmpty()) {
            List<Long> fIds = fCards.stream().map(Flashcard::getId).toList();
            flashcardReviewLogMapper.delete(new LambdaQueryWrapper<FlashcardReviewLog>()
                    .in(FlashcardReviewLog::getFlashcardId, fIds));
        }

        // 1.2 闪卡
        flashcardMapper.delete(new LambdaQueryWrapper<Flashcard>()
                .eq(Flashcard::getDocId, id));

        // 1.3 答题记录
        quizAttemptMapper.delete(new LambdaQueryWrapper<QuizAttempt>()
                .eq(QuizAttempt::getDocId, id));

        // 1.4 测验题
        quizMapper.delete(new LambdaQueryWrapper<Quiz>()
                .eq(Quiz::getDocId, id));

        // 1.5 学习笔记
        studyNoteMapper.delete(new LambdaQueryWrapper<StudyNote>()
                .eq(StudyNote::getDocId, id));

        // 1.6 思维导图
        mindMapMapper.delete(new LambdaQueryWrapper<MindMap>()
                .eq(MindMap::getDocId, id));

        // 1.7 FlowNote
        flowNoteMapper.delete(new LambdaQueryWrapper<FlowNote>()
                .eq(FlowNote::getDocId, id));

        // 1.8 卡片组
        cardDeckMapper.delete(new LambdaQueryWrapper<CardDeck>()
                .eq(CardDeck::getDocId, id));

        // 1.9 对话消息（依赖 conversations 的 sessionId）
        List<Conversation> convs = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>().eq(Conversation::getDocId, id));
        if (!convs.isEmpty()) {
            List<String> sessionIds = convs.stream()
                    .map(Conversation::getSessionId)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();
            if (!sessionIds.isEmpty()) {
                chatHistoryMapper.delete(new LambdaQueryWrapper<ChatHistory>()
                        .in(ChatHistory::getSessionId, sessionIds));
            }
        }

        // 1.10 对话线程
        conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getDocId, id));

        // 1.11 删除关联会话
        sessionMapper.delete(new LambdaQueryWrapper<Session>()
                .eq(Session::getDocId, id));

        // 1.12 删除文档大纲
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

    // ═══════ 重试处理 ═══════

    /**
     * 重试失败文档的向量化处理：
     * 1. 校验文档状态和文件存在性
     * 2. 清理旧切块数据，重置状态为 UPLOADING（事务内）
     * 3. 事务提交后发送 RabbitMQ 消息（失败时将状态回滚为 FAILED）
     */
    @Transactional
    public void retryAndSendMessage(Long id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }
        if (!"FAILED".equals(doc.getStatus())) {
            throw new IllegalArgumentException("仅 FAILED 状态的文档可重试，当前状态: " + doc.getStatus());
        }
        if (doc.getFilePath() == null || !Files.exists(Path.of(doc.getFilePath()))) {
            throw new IllegalArgumentException("原始文件已丢失，无法重试，请重新上传");
        }

        // 清理旧的切块数据
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, id));

        // 重置状态
        updateStatus(id, "UPLOADING", null, null, null);

        // 事务提交后再发送消息，避免状态回滚不一致
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    boolean sent = embeddingProducer.sendEmbeddingTask(
                            doc.getDocumentId(), doc.getFilePath(), doc.getFileName(), doc.getUserId());
                    if (!sent) {
                        updateStatus(id, "FAILED", null, null, "消息队列不可用，请稍后重试");
                        log.error("重试消息发送失败, 已回滚状态为 FAILED: docId={}", id);
                    } else {
                        log.info("文档重试已提交: id={}, fileName={}", id, doc.getFileName());
                    }
                } catch (Exception e) {
                    log.error("afterCommit 异常, 尝试回滚状态为 FAILED: docId={}", id, e);
                    try { updateStatus(id, "FAILED", null, null, "重试异常: " + e.getMessage()); } catch (Exception ignored) {}
                }
            }
        });
    }

    // ═══════ 文件名清洗 ═══════

    /** 匹配 HTML 标签和危险字符 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern DANGEROUS_CHARS = Pattern.compile("[\"'\\\\;`$|&<>]");

    /**
     * 清洗用户上传的原始文件名：
     * 1. 去除 HTML 标签
     * 2. 去除危险字符
     * 3. 压缩连续空白
     * 4. 截断到 200 字符
     */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "未命名文件";
        String clean = HTML_TAG.matcher(name).replaceAll("");
        clean = DANGEROUS_CHARS.matcher(clean).replaceAll("");
        clean = clean.replaceAll("\\s+", " ").trim();
        if (clean.length() > 200) clean = clean.substring(0, 200).trim();
        // 去掉清洗后只剩扩展名的情况
        if (clean.startsWith(".")) clean = "未命名文件" + clean;
        return clean.isEmpty() ? "未命名文件" : clean;
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
    public Map<String, Object> upload(String originalName, long fileSize, InputStream inputStream) {
        String extension = getSupportedExtension(originalName);
        if (extension == null) {
            throw new IllegalArgumentException("仅支持 PDF、Word、PPT、TXT、Markdown、HTML、Excel、CSV、图片(JPG/PNG/BMP/TIFF) 文件");
        }

        // 文件大小二次校验（multipart 配置之外的兜底）
        if (fileSize > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小超过限制（最大 50MB），请压缩后重试");
        }

        // 魔数字节校验 — 防止伪造扩展名上传可执行文件等危险类型
        BufferedInputStream bufferedIn = new BufferedInputStream(inputStream, 8192);
        bufferedIn.mark(8);
        try {
            FileMagicValidator.validate(bufferedIn, extension);
            bufferedIn.reset();
        } catch (IllegalArgumentException e) {
            throw e; // 校验失败直接抛出
        } catch (IOException e) {
            log.warn("文件魔数校验读取失败: {}", e.getMessage());
            throw new IllegalArgumentException("文件内容读取失败，请重新上传");
        }

        // 清洗文件名
        String safeName = sanitizeFileName(originalName);
        // 确保清洗后的文件名仍保留扩展名
        if (!safeName.toLowerCase(Locale.ROOT).endsWith("." + extension)) {
            safeName = safeName.replaceAll("(?i)\\.[^.]+$", "") + "." + extension;
        }

        Long userId = SecurityUtils.getCurrentUserId();

        // 并发去重：同一用户 60 秒内上传同名同大小文件视为重复
        Document recentDuplicate = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId)
                        .eq(Document::getFileName, safeName)
                        .eq(Document::getFileSize, fileSize)
                        .ge(Document::getCreatedAt, java.time.LocalDateTime.now().minusSeconds(60))
                        .last("LIMIT 1"));
        if (recentDuplicate != null) {
            log.warn("检测到重复上传: userId={}, fileName={}, 跳过", userId, safeName);
            Session existingSession = sessionMapper.selectOne(
                    new LambdaQueryWrapper<Session>().eq(Session::getDocId, recentDuplicate.getId()).last("LIMIT 1"));
            Long sessionId = existingSession != null ? existingSession.getId() : 0L;
            return Map.of(
                    "documentId", recentDuplicate.getDocumentId(),
                    "sessionId", sessionId,
                    "fileName", safeName,
                    "size", fileSize,
                    "status", recentDuplicate.getStatus()
            );
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
            Files.copy(bufferedIn, filePath);
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }

        log.info("文件上传成功: uuid={}, name={}, size={}", uuid, safeName, fileSize);

        Document doc = Document.builder()
                .userId(userId)
                .documentId(uuid)
                .title(safeName.replaceAll("(?i)\\.[^.]+$", ""))
                .fileName(safeName)
                .fileSize(fileSize)
                .fileType(extension)
                .filePath(filePath.toString())
                .status("UPLOADING")
                .build();
        create(doc);

        Session session = sessionService.create(userId, doc.getId(), doc.getTitle());

        // 事务提交后再发送 MQ 消息，避免事务回滚时消费者处理不存在的文档
        final String finalUuid = uuid;
        final String finalPath = filePath.toString();
        final String finalName = safeName;
        final Long finalDocId = doc.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    boolean sent = embeddingProducer.sendEmbeddingTask(finalUuid, finalPath, finalName, userId);
                    if (!sent) {
                        updateStatus(finalDocId, "FAILED", null, null, "消息队列不可用，请稍后重试");
                        log.error("上传消息发送失败, 已回滚状态为 FAILED: docId={}", finalDocId);
                    }
                } catch (Exception e) {
                    log.error("afterCommit 发送 MQ 异常, 尝试回滚状态为 FAILED: docId={}", finalDocId, e);
                    try { updateStatus(finalDocId, "FAILED", null, null, "发送异常: " + e.getMessage()); } catch (Exception ignored) {}
                }
            }
        });

        return Map.of(
                "documentId", uuid,
                "sessionId", session.getId(),
                "fileName", safeName,
                "size", fileSize,
                "status", "processing"
        );
    }

    private String getSupportedExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "pdf", "doc", "docx", "ppt", "pptx", "txt", "md", "html", "htm", "xlsx", "csv",
                 "jpg", "jpeg", "png", "bmp", "tiff" -> extension;
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
     * 重新生成文档大纲 — 先删除旧大纲，再重新生成
     * 不加 @Transactional: 删除和生成分开提交，避免 LLM 长耗时期间持锁
     */
    public OutlineResponse regenerateOutline(Long docId) {
        Document doc = getById(docId);
        if (doc == null) throw new IllegalArgumentException("文档不存在");
        if (!"COMPLETED".equals(doc.getStatus())) throw new IllegalArgumentException("文档尚未处理完成");
        if (doc.getChunkCount() == null || doc.getChunkCount() == 0) throw new IllegalArgumentException("文档无切块数据");

        // 先删除旧大纲（独立事务），否则 generateAndSave 检测到已存在会跳过
        outlineService.deleteByDocId(docId);

        DocumentOutline newOutline = outlineGenerator.generateAndSave(
                docId, SecurityUtils.getCurrentUserId(), doc.getChunkCount());
        if (newOutline == null) throw new IllegalStateException("大纲生成失败, 请稍后重试");

        return OutlineResponse.from(newOutline, objectMapper);
    }
}
