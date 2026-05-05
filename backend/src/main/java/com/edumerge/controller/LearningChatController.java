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

        // sessionId 优先: 解析为 documentUuid 用于 Milvus 过滤
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

        if (message == null || message.isBlank()) {
            SseEmitter err = new SseEmitter();
            err.completeWithError(new IllegalArgumentException("消息不能为空"));
            return err;
        }

        SseEmitter emitter = new SseEmitter(120_000L);
        log.info("流式对话请求: message='{}', documentId='{}'", message, finalDocId);

        CompletableFuture.runAsync(() -> {
            try {
                List<EmbeddingMatch<TextSegment>> matches =
                        aiRagService.retrieveMatches(message, finalDocId);

                if (matches.isEmpty()) {
                    String fallback = "在该材料中未找到相关内容。";
                    emit(emitter, Map.of("token", fallback));
                    emit(emitter, Map.of("sources", List.of()));
                    emitDone(emitter);
                    emitter.complete();
                    return;
                }

                // 使用 chatStream 结合 ChatMemory, 自动管理对话记忆
                aiRagService.chatStream(message, finalDocId, finalSessionId,
                        new StreamingResponseHandler<AiMessage>() {
                            @Override
                            public void onNext(String token) {
                                emit(emitter, Map.of("token", token));
                            }

                            @Override
                            public void onComplete(Response<AiMessage> response) {
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
                                String errMsg = "生成回答失败: " + error.getMessage();
                                emit(emitter, Map.of("error", errMsg));
                                emitDone(emitter);
                                emitter.complete();
                            }
                        });
            } catch (Exception e) {
                log.error("流式对话异常: {}", e.getMessage(), e);
                emit(emitter, Map.of("error", "系统异常: " + e.getMessage()));
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
