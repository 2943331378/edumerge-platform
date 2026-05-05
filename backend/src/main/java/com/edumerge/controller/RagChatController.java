package com.edumerge.controller;

import com.edumerge.ai.AiRagService;
import com.edumerge.common.result.Result;
import com.edumerge.entity.ChatHistory;
import com.edumerge.service.ChatHistoryService;
import com.edumerge.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG 对话接口 — AI 逻辑委托给 AiRagService, 持久化委托给 ChatHistoryService
 */
@Slf4j
@RestController
@RequestMapping("/rag")
public class RagChatController {

    private final AiRagService aiRagService;
    private final ChatHistoryService chatHistoryService;
    private final SessionService sessionService;

    @Autowired
    public RagChatController(AiRagService aiRagService,
                             ChatHistoryService chatHistoryService,
                             SessionService sessionService) {
        this.aiRagService = aiRagService;
        this.chatHistoryService = chatHistoryService;
        this.sessionService = sessionService;
    }

    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String documentId = body.get("documentId");
        String sessionIdStr = body.get("sessionId");

        // sessionId 优先: 解析为 documentUuid
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                documentId = sessionService.resolveDocUuid(Long.parseLong(sessionIdStr));
            } catch (Exception e) {
                log.warn("sessionId 解析失败: {}", e.getMessage());
            }
        }

        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }

        log.info("RAG 对话请求: message='{}', documentId='{}'", message, documentId);

        AiRagService.AiRagResult result = aiRagService.chat(message, documentId, sessionIdStr);

        if (result.isSuccess()) {
            return Result.success("RAG 回答生成成功", Map.of(
                    "answer", result.getAnswer(),
                    "sources", result.getSources()
            ));
        } else {
            return Result.fail(result.getMessage());
        }
    }

    @GetMapping("/history")
    public Result<List<ChatHistory>> history(@RequestParam(required = false) String sessionId) {
        return Result.success(chatHistoryService.listBySession(sessionId, 100));
    }
}
