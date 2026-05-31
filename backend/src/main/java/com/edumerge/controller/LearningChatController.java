package com.edumerge.controller;

import com.edumerge.ai.AiRagService;
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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

    @Autowired
    public LearningChatController(AiRagService aiRagService, SessionService sessionService) {
        this.aiRagService = aiRagService;
        this.sessionService = sessionService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> body) {
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

        SseEmitter emitter = new SseEmitter(300_000L);
        log.info("流式对话请求: message='{}', documentId='{}'", message, finalDocId);

        // 客户端断开时标记取消，避免后台任务继续占用资源
        final boolean[] cancelled = {false};
        emitter.onTimeout(() -> {
            cancelled[0] = true;
            log.warn("SSE 超时 (300s), 客户端可能已断开");
        });
        emitter.onError(throwable -> {
            cancelled[0] = true;
            log.warn("SSE 连接错误: {}", throwable.getMessage());
        });
        emitter.onCompletion(() -> log.debug("SSE 连接正常关闭"));

        CompletableFuture.runAsync(() -> {
            if (cancelled[0]) return;
            try {
                List<EmbeddingMatch<TextSegment>> matches =
                        aiRagService.retrieveMatches(message, finalDocId);

                if (cancelled[0]) { emitter.complete(); return; }

                if (matches.isEmpty()) {
                    emit(emitter, Map.of("token", "在该材料中未找到相关内容。"));
                    emit(emitter, Map.of("sources", List.of()));
                    emitDone(emitter);
                    emitter.complete();
                    return;
                }

                aiRagService.chatStream(message, finalDocId, finalSessionId,
                        finalDocId2, finalActivityType, finalContextHint,
                        new StreamingResponseHandler<AiMessage>() {
                            @Override
                            public void onNext(String token) {
                                if (!cancelled[0]) emit(emitter, Map.of("token", token));
                            }

                            @Override
                            public void onComplete(Response<AiMessage> response) {
                                if (cancelled[0]) { emitter.complete(); return; }
                                List<Map<String, Object>> sources = matches.stream()
                                        .map(m -> Map.<String, Object>of(
                                                "index", matches.indexOf(m) + 1,
                                                "content", m.embedded().text(),
                                                "score", m.score()))
                                        .toList();
                                emit(emitter, Map.of("sources", sources));
                                emitDone(emitter);
                                emitter.complete();
                            }

                            @Override
                            public void onError(Throwable error) {
                                log.error("流式生成错误: {}", error.getMessage(), error);
                                String errMsg = error.getMessage() != null ? error.getMessage() : "未知错误";
                                emit(emitter, Map.of("error", errMsg));
                                emitDone(emitter);
                                emitter.complete();
                            }
                        });
            } catch (Exception e) {
                log.error("流式对话异常: {}", e.getMessage(), e);
                emit(emitter, Map.of("error", "系统异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误")));
                emitDone(emitter);
                emitter.complete();
            }
        });

        return emitter;
    }

    private void emit(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.debug("SSE 推送失败: {}", e.getMessage());
        }
    }

    private void emitDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (IOException e) {
            log.debug("SSE [DONE] 推送失败: {}", e.getMessage());
        }
    }
}
