package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.*;
import com.edumerge.entity.CardDeck;
import com.edumerge.entity.Flashcard;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.CardDeckService;
import com.edumerge.service.DocumentService;
import com.edumerge.service.FlashcardService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 学习卡片接口 — 业务逻辑委托给 FlashcardService
 */
@Slf4j
@RestController
@RequestMapping("/flashcards")
public class FlashcardController {

    private final FlashcardService flashcardService;
    private final DocumentService documentService;
    private final CardDeckService cardDeckService;

    @Autowired
    public FlashcardController(FlashcardService flashcardService, DocumentService documentService,
                               CardDeckService cardDeckService) {
        this.flashcardService = flashcardService;
        this.documentService = documentService;
        this.cardDeckService = cardDeckService;
    }

    @PostMapping("/generate")
    public Result<List<FlashcardResponse>> generate(@RequestBody GenerateRequest body) {
        documentService.verifyOwnership(Long.parseLong(body.getDocId()));
        Integer startChunk = body.getStartChunk() != null ? Integer.parseInt(body.getStartChunk()) : null;
        Integer endChunk = body.getEndChunk() != null ? Integer.parseInt(body.getEndChunk()) : null;
        List<Flashcard> cards = flashcardService.generate(
                body.getDocId(), body.getDocUuid(), body.getSessionId(), body.getSectionContext(), startChunk, endChunk);
        return Result.success("学习卡片生成成功", DtoMapper.toFlashcardResponseList(cards));
    }

    @GetMapping
    public Result<List<FlashcardResponse>> list(@RequestParam(required = false) Long docId,
                                                @RequestParam(required = false) Long sessionId,
                                                @RequestParam(required = false) Long deckId,
                                                @RequestParam(required = false) Boolean important) {
        List<Flashcard> cards;
        if (deckId != null) {
            CardDeck deck = cardDeckService.getById(deckId);
            if (deck == null) throw new IllegalArgumentException("卡片组不存在: " + deckId);
            documentService.verifyOwnership(deck.getDocId());
            cards = Boolean.TRUE.equals(important)
                    ? flashcardService.listImportantByDeckId(deckId)
                    : flashcardService.listByDeckId(deckId);
        } else {
            if (sessionId != null) docId = flashcardService.resolveDocId(sessionId);
            if (docId != null) documentService.verifyOwnership(docId);
            if (docId != null) {
                cards = Boolean.TRUE.equals(important)
                        ? flashcardService.listImportantByDocId(docId)
                        : flashcardService.listByDocId(docId);
            } else {
                cards = List.of();
            }
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

    @PutMapping("/{id}/important")
    public Result<FlashcardResponse> toggleImportant(@PathVariable Long id) {
        Flashcard card = flashcardService.toggleImportant(id);
        return Result.success("重要标记已更新", DtoMapper.toResponse(card));
    }

    @GetMapping("/due")
    public Result<List<FlashcardResponse>> listDue(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(DtoMapper.toFlashcardResponseList(flashcardService.listDueCards(docId)));
    }
}
