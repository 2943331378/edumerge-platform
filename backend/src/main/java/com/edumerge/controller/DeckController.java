package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.CardDeckResponse;
import com.edumerge.dto.DtoMapper;
import com.edumerge.entity.CardDeck;
import com.edumerge.service.CardDeckService;
import com.edumerge.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/decks")
public class DeckController {

    private final CardDeckService cardDeckService;
    private final DocumentService documentService;

    @Autowired
    public DeckController(CardDeckService cardDeckService, DocumentService documentService) {
        this.cardDeckService = cardDeckService;
        this.documentService = documentService;
    }

    @GetMapping
    public Result<List<CardDeckResponse>> list(@RequestParam(required = false) Long docId,
                                                @RequestParam(required = false) String type) {
        if (docId != null) documentService.verifyOwnership(docId);
        return Result.success(DtoMapper.toDeckResponseList(cardDeckService.listByDocIdAndType(docId, type)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        CardDeck deck = cardDeckService.getById(id);
        if (deck != null) documentService.verifyOwnership(deck.getDocId());
        cardDeckService.delete(id);
        return Result.success("卡片组已删除", null);
    }
}
