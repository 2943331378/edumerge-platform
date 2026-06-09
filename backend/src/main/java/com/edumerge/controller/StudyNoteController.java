package com.edumerge.controller;

import com.edumerge.ai.AiNoteGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.CardDeck;
import com.edumerge.entity.Document;
import com.edumerge.entity.StudyNote;
import com.edumerge.service.CardDeckService;
import com.edumerge.service.DocumentService;
import com.edumerge.service.StudyNoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 学习笔记接口 — 业务逻辑委托给 StudyNoteService
 */
@Slf4j
@RestController
@RequestMapping("/notes")
public class StudyNoteController {

    private final StudyNoteService studyNoteService;
    private final DocumentService documentService;
    private final AiNoteGenerator aiNoteGenerator;
    private final CardDeckService cardDeckService;
    private final ExecutorService asyncExecutor;

    @Autowired
    public StudyNoteController(StudyNoteService studyNoteService, DocumentService documentService,
                                AiNoteGenerator aiNoteGenerator, CardDeckService cardDeckService,
                                @Qualifier("asyncExecutor") ExecutorService asyncExecutor) {
        this.studyNoteService = studyNoteService;
        this.documentService = documentService;
        this.aiNoteGenerator = aiNoteGenerator;
        this.cardDeckService = cardDeckService;
        this.asyncExecutor = asyncExecutor;
    }

    @GetMapping
    public Result<Map<String, Object>> getNote(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        StudyNote note = studyNoteService.getByDocId(docId);
        if (note == null) {
            return Result.fail("该文档暂无学习笔记");
        }
        return Result.success(studyNoteService.toMap(note));
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> getHistory(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        List<StudyNote> notes = studyNoteService.listByDocId(docId);
        return Result.success(notes.stream().map(studyNoteService::toMap).toList());
    }

    // TODO: 替换 Map<String, String> 为 UpdateNoteRequest DTO（含 content, title 字段）
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String content = body.get("content");
        String title = body.get("title");
        if (content == null && title == null) {
            return Result.fail("content 和 title 不能同时为空");
        }
        StudyNote updated = studyNoteService.update(id, content, title);
        return Result.success(studyNoteService.toMap(updated));
    }

    // TODO: 替换 Map<String, String> 为 GenerateNoteRequest DTO（含 docId, requirements, sectionContext, startChunk, endChunk 字段）
    @PostMapping("/generate")
    public Result<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        String docIdStr = body.get("docId");
        if (docIdStr == null || docIdStr.isBlank()) {
            return Result.fail("docId 不能为空");
        }
        Long docId = Long.parseLong(docIdStr);
        documentService.verifyOwnership(docId);
        Integer startChunk = body.get("startChunk") != null ? Integer.parseInt(body.get("startChunk")) : null;
        Integer endChunk = body.get("endChunk") != null ? Integer.parseInt(body.get("endChunk")) : null;
        Map<String, Object> data = studyNoteService.generate(docId, body.get("requirements"), body.get("sectionContext"), startChunk, endChunk);
        return Result.success(data);
    }

    /** 流式生成笔记 — SSE 逐 token 推送，前端实时渲染 */
    // TODO: 替换 Map<String, String> 为 GenerateNoteRequest DTO（复用 /generate 的 DTO）
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> body, HttpServletResponse response) {
        String docIdStr = body.get("docId");
        if (docIdStr == null || docIdStr.isBlank()) {
            SseEmitter err = new SseEmitter();
            err.completeWithError(new IllegalArgumentException("docId 不能为空"));
            return err;
        }
        Long docId = Long.parseLong(docIdStr);
        documentService.verifyOwnership(docId);

        Document doc = documentService.getById(docId);
        if (doc == null || !"COMPLETED".equalsIgnoreCase(doc.getStatus())) {
            SseEmitter err = new SseEmitter();
            err.completeWithError(new IllegalArgumentException("文档不存在或未完成向量化"));
            return err;
        }

        Integer startChunk = body.get("startChunk") != null ? Integer.parseInt(body.get("startChunk")) : null;
        Integer endChunk = body.get("endChunk") != null ? Integer.parseInt(body.get("endChunk")) : null;
        String requirements = body.get("requirements");
        String sectionContext = body.get("sectionContext");

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);

        emitter.onTimeout(() -> { cancelled.set(true); log.warn("笔记 SSE 超时"); });
        emitter.onError(t -> { cancelled.set(true); log.warn("笔记 SSE 错误: {}", t.getMessage()); });
        emitter.onCompletion(() -> { cancelled.set(true); });

        final String docUuid = doc.getDocumentId();
        // P2: 进度估算 — 笔记预估 2500 tokens
        final int estimatedTokens = 2500;
        final int[] tokenCount = {0};

        CompletableFuture.runAsync(() -> {
            StringBuilder tokenBuffer = new StringBuilder();
            final long[] lastFlush = {System.currentTimeMillis()};
            try {
                AiNoteGenerator.StudyNoteResult result = aiNoteGenerator.generateStream(
                        docId, docUuid, requirements, sectionContext, startChunk, endChunk,
                        token -> {
                            if (cancelled.get()) return;
                            tokenBuffer.append(token);
                            tokenCount[0]++;
                            long now = System.currentTimeMillis();
                            if (now - lastFlush[0] >= 50) {
                                int progress = Math.min(95, tokenCount[0] * 100 / estimatedTokens);
                                emit(emitter, cancelled, Map.of("token", tokenBuffer.toString(), "progress", progress));
                                tokenBuffer.setLength(0);
                                lastFlush[0] = now;
                            }
                        }
                );
                if (!cancelled.get() && !tokenBuffer.isEmpty()) {
                    int progress = Math.min(95, tokenCount[0] * 100 / estimatedTokens);
                    emit(emitter, cancelled, Map.of("token", tokenBuffer.toString(), "progress", progress));
                }

                if (cancelled.get()) { sendDoneAndComplete(emitter, response, completed); return; }

                if (result.isSuccess()) {
                    // 持久化
                    CardDeck deck = cardDeckService.create(docId, "NOTE", result.getTitle());
                    StudyNote saved = studyNoteService.create(docId, deck.getId(), result.getTitle(),
                            result.getContent(), result.getSourceSummary(), result.getRequirements());

                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("deckId", deck.getId());
                    meta.put("docId", docId);
                    meta.put("title", result.getTitle());
                    meta.put("sourceSummary", result.getSourceSummary());
                    meta.put("createdAt", saved.getCreatedAt() != null ? saved.getCreatedAt().toString() : null);
                    emit(emitter, cancelled, Map.of("done", meta));
                } else {
                    emit(emitter, cancelled, Map.of("error", "笔记生成失败: 未从文档中提取到足够内容"));
                }
                sendDoneAndComplete(emitter, response, completed);
            } catch (Exception e) {
                log.error("流式笔记异常: {}", e.getMessage(), e);
                if (!cancelled.get()) emit(emitter, cancelled, Map.of("error", "系统异常: " + e.getMessage()));
                sendDoneAndComplete(emitter, response, completed);
            }
        }, asyncExecutor);

        return emitter;
    }

    private void emit(SseEmitter emitter, AtomicBoolean cancelled, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            cancelled.set(true);
        }
    }

    private void sendDoneAndComplete(SseEmitter emitter, HttpServletResponse response, AtomicBoolean doneGuard) {
        if (!doneGuard.compareAndSet(false, true)) return;
        try {
            emitter.send(SseEmitter.event().data("[DONE]"));
            if (response != null) response.flushBuffer();
        } catch (Exception ignored) {}
        try { Thread.sleep(200); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try { emitter.complete(); } catch (Exception ignored) {}
    }
}
