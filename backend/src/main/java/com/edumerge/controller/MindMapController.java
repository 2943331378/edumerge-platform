package com.edumerge.controller;

import com.edumerge.ai.AiMindMapGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.CardDeck;
import com.edumerge.entity.Document;
import com.edumerge.entity.MindMap;
import com.edumerge.service.CardDeckService;
import com.edumerge.service.DocumentService;
import com.edumerge.service.MindMapService;
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

/**
 * 思维导图接口 — 业务逻辑委托给 MindMapService
 */
@Slf4j
@RestController
@RequestMapping("/mindmap")
public class MindMapController {

    private final MindMapService mindMapService;
    private final DocumentService documentService;
    private final AiMindMapGenerator aiMindMapGenerator;
    private final CardDeckService cardDeckService;
    private final ExecutorService asyncExecutor;

    @Autowired
    public MindMapController(MindMapService mindMapService, DocumentService documentService,
                              AiMindMapGenerator aiMindMapGenerator, CardDeckService cardDeckService,
                              @Qualifier("asyncExecutor") ExecutorService asyncExecutor) {
        this.mindMapService = mindMapService;
        this.documentService = documentService;
        this.aiMindMapGenerator = aiMindMapGenerator;
        this.cardDeckService = cardDeckService;
        this.asyncExecutor = asyncExecutor;
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> listMindMaps(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(mindMapService.listMindMaps(docId));
    }

    @GetMapping("/detail")
    public Result<Map<String, Object>> getMindMapDetail(@RequestParam Long deckId) {
        mindMapService.verifyOwnershipByDeckId(deckId);
        return Result.success(mindMapService.getMindMapDetail(deckId));
    }

    @PostMapping("/generate")
    public Result<Map<String, Object>> generateMindMap(@RequestParam Long docId,
                                                       @RequestParam(required = false) String sectionContext,
                                                       @RequestParam(required = false) Integer startChunk,
                                                       @RequestParam(required = false) Integer endChunk) {
        documentService.verifyOwnership(docId);
        return Result.success(mindMapService.generate(docId, sectionContext, startChunk, endChunk));
    }

    /** 流式生成思维导图 — SSE 逐 token 推送 + 进度估算 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam Long docId,
                              @RequestParam(required = false) String sectionContext,
                              @RequestParam(required = false) Integer startChunk,
                              @RequestParam(required = false) Integer endChunk,
                              HttpServletResponse response) {
        documentService.verifyOwnership(docId);
        Document doc = documentService.getById(docId);
        if (doc == null || doc.getDocumentId() == null || doc.getDocumentId().isBlank()) {
            SseEmitter err = new SseEmitter();
            err.completeWithError(new IllegalArgumentException("文档不存在或未完成向量化"));
            return err;
        }

        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        emitter.onTimeout(() -> { cancelled.set(true); log.warn("思维导图 SSE 超时"); });
        emitter.onError(t -> { cancelled.set(true); });
        emitter.onCompletion(() -> { cancelled.set(true); });

        final String docUuid = doc.getDocumentId();
        // P2: 进度估算 — 思维导图预估 1200 tokens
        final int estimatedTokens = 1200;
        final int[] tokenCount = {0};

        CompletableFuture.runAsync(() -> {
            StringBuilder tokenBuffer = new StringBuilder();
            final long[] lastFlush = {System.currentTimeMillis()};
            try {
                AiMindMapGenerator.MindMapResult result = aiMindMapGenerator.generateStream(
                        docId, docUuid, sectionContext, startChunk, endChunk,
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
                    emit(emitter, cancelled, Map.of("token", tokenBuffer.toString(), "progress", 95));
                }

                if (cancelled.get()) { sendDoneAndComplete(emitter, response, completed); return; }

                if (result.isSuccess()) {
                    CardDeck deck = cardDeckService.create(docId, "MINDMAP", result.getTitle());
                    MindMap saved = mindMapService.create(docId, deck.getId(), result.getContent());
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("deckId", deck.getId());
                    meta.put("docId", docId);
                    meta.put("title", result.getTitle());
                    meta.put("content", result.getContent());
                    meta.put("createdAt", saved.getCreatedAt() != null ? saved.getCreatedAt().toString() : null);
                    emit(emitter, cancelled, Map.of("done", meta, "progress", 100));
                } else {
                    emit(emitter, cancelled, Map.of("error", "思维导图生成失败: 未从文档中提取到足够内容"));
                }
                sendDoneAndComplete(emitter, response, completed);
            } catch (Exception e) {
                log.error("流式思维导图异常: {}", e.getMessage(), e);
                if (!cancelled.get()) emit(emitter, cancelled, Map.of("error", "系统异常: " + e.getMessage()));
                sendDoneAndComplete(emitter, response, completed);
            }
        }, asyncExecutor);

        return emitter;
    }

    @DeleteMapping("/{deckId}")
    public Result<Void> deleteMindMap(@PathVariable Long deckId) {
        mindMapService.deleteMindMap(deckId);
        return Result.success(null);
    }

    /** 兼容旧接口: GET /mindmap?docId= → 返回最新一条或自动生成 */
    @GetMapping
    public Result<Map<String, Object>> getMindMap(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(mindMapService.getOrGenerate(docId));
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
        try { emitter.complete(); } catch (Exception ignored) {}
    }
}
