package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.CardDeckResponse;
import com.edumerge.dto.DtoMapper;
import com.edumerge.entity.CardDeck;
import com.edumerge.service.CardDeckService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/decks")
public class DeckController {

    private final CardDeckService cardDeckService;

    @Autowired
    public DeckController(CardDeckService cardDeckService) {
        this.cardDeckService = cardDeckService;
    }

    @GetMapping
    public Result<List<CardDeckResponse>> list(@RequestParam(required = false) Long docId,
                                                @RequestParam(required = false) String type) {
        return Result.success(DtoMapper.toDeckResponseList(cardDeckService.listByDocIdAndType(docId, type)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        cardDeckService.delete(id);
        return Result.success("卡片组已删除", null);
    }
}
