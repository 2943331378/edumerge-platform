package com.edumerge.controller;

import com.edumerge.ai.AiFlashcardGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.Flashcard;
import com.edumerge.service.FlashcardService;
import com.edumerge.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学习卡片接口 — 生成委托给 AiFlashcardGenerator, 查询委托给 FlashcardService
 */
@Slf4j
@RestController
@RequestMapping("/flashcards")
public class FlashcardController {

    private final FlashcardService flashcardService;
    private final AiFlashcardGenerator aiFlashcardGenerator;
    private final SessionService sessionService;

    @Autowired
    public FlashcardController(FlashcardService flashcardService,
                               AiFlashcardGenerator aiFlashcardGenerator,
                               SessionService sessionService) {
        this.flashcardService = flashcardService;
        this.aiFlashcardGenerator = aiFlashcardGenerator;
        this.sessionService = sessionService;
    }

    @PostMapping("/generate")
    public Result<List<Flashcard>> generate(@RequestBody Map<String, String> body) {
        String docIdStr = body.get("docId");
        String docUuid = body.get("docUuid");
        String sessionIdStr = body.get("sessionId");

        // sessionId 优先: 解析为 docId + docUuid
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                Long sid = Long.parseLong(sessionIdStr);
                docIdStr = String.valueOf(sessionService.resolveDocId(sid));
                docUuid = sessionService.resolveDocUuid(sid);
            } catch (Exception e) {
                return Result.fail("会话无效: " + e.getMessage());
            }
        }

        if (docIdStr == null || docUuid == null) return Result.fail("docId/sessionId 和 docUuid 不能为空");

        List<Flashcard> cards = aiFlashcardGenerator.generate(Long.parseLong(docIdStr), 1L, docUuid);
        return cards.isEmpty() ? Result.fail("未检索到文档内容，生成失败") : Result.success("学习卡片生成成功", cards);
    }

    @GetMapping
    public Result<List<Flashcard>> list(@RequestParam(required = false) Long docId,
                                        @RequestParam(required = false) Long sessionId) {
        if (sessionId != null) docId = sessionService.resolveDocId(sessionId);
        return Result.success(docId != null ? flashcardService.listByDocId(docId) : List.of());
    }
}
