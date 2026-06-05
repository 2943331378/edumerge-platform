package com.edumerge.controller;

import com.edumerge.ai.AiRagService;
import com.edumerge.service.DocumentService;
import com.edumerge.service.SessionService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式 RAG 对话接口
 * 控制器仅负责 HTTP 参数解析与 SSE 推送, 所有业务逻辑委托给 RagChatService
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class LearningChatController {

    private final AiRagService aiRagService;
    private final SessionService sessionService;
    private final DocumentService documentService;

    @Autowired
    public LearningChatController(AiRagService aiRagService, SessionService sessionService,
                                  DocumentService documentService) {
        this.aiRagService = aiRagService;
        this.sessionService = sessionService;
        this.documentService = documentService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> body,
                              HttpServletResponse response) {
        String message = body.get("message");
        String documentId = body.get("documentId");
        String sessionIdStr = body.get("sessionId");
        String docIdStr = body.get("docId");
        String activityType = body.get("activityType");
        String contextHint = body.get("contextHint");

        // 解析 docId（数据库主键，用于 FlowNote 精确关联）
        Long docId = null;
        if (docIdStr != null && !docIdStr.isBlank()) {
            try { docId = Long.parseLong(docIdStr); } catch (NumberFormatException ignored) {}
        }
        if (docId != null) documentService.verifyOwnership(docId);
        final Long finalDocId2 = docId;

        String resolvedDocId = documentId;
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                resolvedDocId = sessionService.resolveDocUuid(Long.parseLong(sessionIdStr));
            } catch (Exception e) {
                log.warn("sessionId 解析失败: {}", e.getMessage());
            }
        }
        final String finalDocId = resolvedDocId;
        final String finalSessionId = sessionIdStr;
        final String finalActivityType = activityType;
        final String finalContextHint = contextHint;

        if (message == null || message.isBlank()) {
            SseEmitter err = new SseEmitter();
            err.completeWithError(new IllegalArgumentException("消息不能为空"));
            return err;
        }

        SseEmitter emitter = new SseEmitter(600_000L);
        log.info("流式对话请求: message='{}', documentId='{}'", message, finalDocId);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false); // 防止 sendDoneAndComplete 重入
        emitter.onTimeout(() -> { cancelled.set(true); log.warn("SSE 超时 (600s)"); });
        emitter.onError(throwable -> { cancelled.set(true); log.warn("SSE 连接错误: {}", throwable.getMessage()); });
        emitter.onCompletion(() -> { cancelled.set(true); log.debug("SSE 连接关闭，标记取消"); });

        // 捕获主线程的 SecurityContext，传递给 ForkJoinPool 异步线程
        // CompletableFuture.runAsync 使用 ForkJoinPool，不继承 ThreadLocal
        SecurityContext securityContext = SecurityContextHolder.getContext();

        CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                if (cancelled.get()) return;
                List<EmbeddingMatch<TextSegment>> matches =
                        aiRagService.retrieveMatches(message, finalDocId);

                if (cancelled.get()) { sendDoneAndComplete(emitter, response, completed); return; }

                aiRagService.chatStream(message, finalDocId, finalSessionId,
                        finalDocId2, finalActivityType, finalContextHint,
                        new StreamingResponseHandler<AiMessage>() {
                            @Override
                            public void onNext(String token) {
                                if (!cancelled.get()) emit(emitter, cancelled, Map.of("token", token));
                            }

                            @Override
                            public void onComplete(Response<AiMessage> aiResponse) {
                                try {
                                    if (cancelled.get()) { sendDoneAndComplete(emitter, response, completed); return; }
                                    List<Map<String, Object>> sources = matches.stream()
                                            .map(m -> Map.<String, Object>of(
                                                    "index", matches.indexOf(m) + 1,
                                                    "content", m.embedded().text(),
                                                    "score", m.score()))
                                            .toList();
                                    emit(emitter, cancelled, Map.of("sources", sources));
                                    sendDoneAndComplete(emitter, response, completed);
                                } catch (Exception e) {
                                    log.error("SSE onComplete 异常: {}", e.getMessage(), e);
                                    sendDoneAndComplete(emitter, response, completed);
                                }
                            }

                            @Override
                            public void onError(Throwable error) {
                                try {
                                    log.error("流式生成错误: {}", error.getMessage(), error);
                                    if (cancelled.get()) { sendDoneAndComplete(emitter, response, completed); return; }
                                    emit(emitter, cancelled, Map.of("error", error.getMessage() != null ? error.getMessage() : "未知错误"));
                                    sendDoneAndComplete(emitter, response, completed);
                                } catch (Exception e) {
                                    log.error("SSE onError 异常: {}", e.getMessage(), e);
                                    sendDoneAndComplete(emitter, response, completed);
                                }
                            }
                        });
            } catch (Exception e) {
                log.error("流式对话异常: {}", e.getMessage(), e);
                if (!cancelled.get()) {
                    emit(emitter, cancelled, Map.of("error", "系统异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误")));
                }
                sendDoneAndComplete(emitter, response, completed);
            }
        });

        return emitter;
    }

    private void emit(SseEmitter emitter, AtomicBoolean cancelled, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            cancelled.set(true);
            log.debug("SSE emit 失败, 标记取消: {}", e.getMessage());
        }
    }

    /** Send raw [DONE] marker, flush to TCP, then complete after a short delay.
     *  The flush + delay prevents ERR_INCOMPLETE_CHUNKED_ENCODING in the browser.
     *  All exceptions are caught to prevent bubbling up to the servlet container,
     *  which would trigger ErrorMvcAutoConfiguration's "Cannot render error page" error.
     *  @param doneGuard prevents multiple concurrent calls from racing on flushBuffer/complete */
    private void sendDoneAndComplete(SseEmitter emitter, HttpServletResponse response, AtomicBoolean doneGuard) {
        if (!doneGuard.compareAndSet(false, true)) return; // 已调用过，跳过
        try {
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (Exception e) {
            log.debug("SSE [DONE] 推送失败: {}", e.getMessage());
        }
        try {
            response.flushBuffer();
        } catch (Exception e) {
            log.debug("SSE flush 失败: {}", e.getMessage());
        }
        // 内联延迟 complete，避免嵌套 CompletableFuture 持有已返回的 Servlet 资源引用
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        try { emitter.complete(); } catch (Exception e) {
            log.debug("SSE complete 失败: {}", e.getMessage());
        }
    }
}
