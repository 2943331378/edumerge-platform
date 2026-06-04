package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.*;
import com.edumerge.entity.Flashcard;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.FlashcardService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学习卡片接口 — 业务逻辑委托给 FlashcardService
 */
@Slf4j
@RestController
@RequestMapping("/flashcards")
public class FlashcardController {

    private final FlashcardService flashcardService;

    @Autowired
    public FlashcardController(FlashcardService flashcardService) {
        this.flashcardService = flashcardService;
    }

    @PostMapping("/generate")
    public Result<List<FlashcardResponse>> generate(@RequestBody Map<String, String> body) {
        List<Flashcard> cards = flashcardService.generate(
                body.get("docId"), body.get("docUuid"), body.get("sessionId"), body.get("sectionContext"));
        return Result.success("学习卡片生成成功", DtoMapper.toFlashcardResponseList(cards));
    }

    @GetMapping
    public Result<List<FlashcardResponse>> list(@RequestParam(required = false) Long docId,
                                                @RequestParam(required = false) Long sessionId,
                                                @RequestParam(required = false) Long deckId) {
        List<Flashcard> cards;
        if (deckId != null) cards = flashcardService.listByDeckId(deckId);
        else {
            if (sessionId != null) docId = flashcardService.resolveDocId(sessionId);
            cards = docId != null ? flashcardService.listByDocId(docId) : List.of();
        }
        return Result.success(DtoMapper.toFlashcardResponseList(cards));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateFlashcardRequest req) {
        Flashcard card = new Flashcard();
        card.setId(id);
        card.setQuestion(req.getQuestion());
        card.setAnswer(req.getAnswer());
        card.setExplanation(req.getExplanation());
        card.setStatus(req.getStatus());
        card.setDifficulty(req.getDifficulty());
        flashcardService.updateById(card);
        return Result.success("卡片已更新", null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (flashcardService.deleteById(id) == 0) return Result.fail("卡片不存在");
        return Result.success("卡片已删除", null);
    }

    @PutMapping("/{id}/review")
    public Result<FlashcardResponse> review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        Flashcard card = flashcardService.review(id, req.getQuality(), SecurityUtils.getCurrentUserId());
        return Result.success("复习记录已保存", DtoMapper.toResponse(card));
    }

    @GetMapping("/due")
    public Result<List<FlashcardResponse>> listDue(@RequestParam Long docId) {
        return Result.success(DtoMapper.toFlashcardResponseList(flashcardService.listDueCards(docId)));
    }
}
