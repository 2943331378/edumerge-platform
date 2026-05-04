package com.edumerge.controller;

import com.edumerge.ai.AiRagService;
import com.edumerge.common.result.Result;
import com.edumerge.entity.ChatHistory;
import com.edumerge.service.ChatHistoryService;
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

    @Autowired
    public RagChatController(AiRagService aiRagService,
                             ChatHistoryService chatHistoryService) {
        this.aiRagService = aiRagService;
        this.chatHistoryService = chatHistoryService;
    }

    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String documentId = body.get("documentId");

        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }

        log.info("RAG 对话请求: message='{}', documentId='{}'", message, documentId);

        AiRagService.AiRagResult result = aiRagService.chat(message, documentId);

        if (result.isSuccess()) {
            chatHistoryService.save(message, result.getAnswer(), result.getSources() != null ? result.getSources().size() : 0);
            return Result.success("RAG 回答生成成功", Map.of(
                    "answer", result.getAnswer(),
                    "sources", result.getSources()
            ));
        } else {
            chatHistoryService.save(message, result.getMessage(), 0);
            return Result.fail(result.getMessage());
        }
    }

    @GetMapping("/history")
    public Result<List<ChatHistory>> history() {
        return Result.success(chatHistoryService.listRecent(100));
    }
}
