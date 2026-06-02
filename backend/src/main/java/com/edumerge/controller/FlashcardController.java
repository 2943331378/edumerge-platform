package com.edumerge.controller;

import com.edumerge.ai.AiFlashcardGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.Flashcard;
import com.edumerge.security.SecurityUtils;
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

        Long docId = Long.parseLong(docIdStr);
        // 加载已有卡片，传问题列表给 AI 以避免重复
        List<String> existingQuestions = flashcardService.listByDocId(docId).stream()
                .map(Flashcard::getQuestion).toList();

        List<Flashcard> cards = aiFlashcardGenerator.generate(docId, SecurityUtils.getCurrentUserId(), docUuid, existingQuestions);
        return cards.isEmpty() ? Result.fail("未检索到文档内容，生成失败") : Result.success("学习卡片生成成功", cards);
    }

    @GetMapping
    public Result<List<Flashcard>> list(@RequestParam(required = false) Long docId,
                                        @RequestParam(required = false) Long sessionId,
                                        @RequestParam(required = false) Long deckId) {
        if (sessionId != null) docId = sessionService.resolveDocId(sessionId);
        if (deckId != null) return Result.success(flashcardService.listByDeckId(deckId));
        return Result.success(docId != null ? flashcardService.listByDocId(docId) : List.of());
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Flashcard card) {
        if (card.getQuestion() != null && card.getQuestion().isBlank()) return Result.fail("问题不能为空");
        if (card.getAnswer() != null && card.getAnswer().isBlank()) return Result.fail("答案不能为空");
        card.setId(id);
        flashcardService.updateById(card);
        return Result.success("卡片已更新", null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (flashcardService.deleteById(id) == 0) return Result.fail("卡片不存在");
        return Result.success("卡片已删除", null);
    }

    /** SM-2 间隔重复: 提交自评 */
    @PutMapping("/{id}/review")
    public Result<Flashcard> review(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer quality = body.get("quality");
        if (quality == null || quality < 1 || quality > 4) {
            return Result.fail("quality 必须在 1-4 之间 (1=忘了 2=模糊 3=记住 4=秒答)");
        }
        try {
            Flashcard card = flashcardService.review(id, quality, SecurityUtils.getCurrentUserId());
            return Result.success("复习记录已保存", card);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 查询到期需复习的卡片 (nextReviewAt <= now 或新卡片) */
    @GetMapping("/due")
    public Result<List<Flashcard>> listDue(@RequestParam Long docId) {
        List<Flashcard> due = flashcardService.listDueCards(docId);
        return Result.success(due);
    }
}
