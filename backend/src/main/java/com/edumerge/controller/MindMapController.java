package com.edumerge.controller;

import com.edumerge.ai.AiMindMapGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.CardDeck;
import com.edumerge.entity.Document;
import com.edumerge.entity.MindMap;
import com.edumerge.service.CardDeckService;
import com.edumerge.service.DocumentService;
import com.edumerge.service.MindMapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 思维导图 Controller — 懒生成策略: 首次请求触发 AI 生成, 后续返回缓存
 */
@Slf4j
@RestController
@RequestMapping("/mindmap")
public class MindMapController {

    private final CardDeckService cardDeckService;
    private final MindMapService mindMapService;
    private final AiMindMapGenerator aiMindMapGenerator;
    private final DocumentService documentService;

    @Autowired
    public MindMapController(CardDeckService cardDeckService,
                             MindMapService mindMapService,
                             AiMindMapGenerator aiMindMapGenerator,
                             DocumentService documentService) {
        this.cardDeckService = cardDeckService;
        this.mindMapService = mindMapService;
        this.aiMindMapGenerator = aiMindMapGenerator;
        this.documentService = documentService;
    }

    @GetMapping
    public Result<Map<String, Object>> getMindMap(@RequestParam Long docId) {
        // 1. 检查是否已有思维导图 deck
        List<CardDeck> decks = cardDeckService.listByDocIdAndType(docId, "MIND_MAP");
        if (!decks.isEmpty()) {
            MindMap existing = mindMapService.getByDocId(docId);
            if (existing != null) {
                CardDeck deck = decks.get(0);
                log.info("返回缓存思维导图: docId={}, deckId={}", docId, deck.getId());
                return Result.success(toMap(existing, deck));
            }
        }

        // 2. 无缓存 → AI 生成
        Document doc = documentService.getById(docId);
        if (doc == null) {
            return Result.fail("文档不存在: " + docId);
        }
        String docUuid = doc.getDocumentId();
        if (docUuid == null || docUuid.isBlank()) {
            return Result.fail("文档尚未完成向量化, 无法生成思维导图: " + docId);
        }

        log.info("开始生成思维导图: docId={}, docUuid={}", docId, docUuid);
        AiMindMapGenerator.MindMapResult genResult = aiMindMapGenerator.generate(docId, 1L, docUuid);

        if (!genResult.isSuccess()) {
            return Result.fail("思维导图生成失败: 未从文档中提取到足够的内容");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", genResult.getDeckId());
        data.put("docId", genResult.getDocId());
        data.put("title", genResult.getTitle());
        data.put("content", genResult.getContent());
        data.put("createdAt", genResult.getCreatedAt());
        return Result.success(data);
    }

    private Map<String, Object> toMap(MindMap mindMap, CardDeck deck) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", mindMap.getDeckId());
        data.put("docId", mindMap.getDocId());
        data.put("title", deck.getTitle());
        data.put("content", mindMap.getContent());
        data.put("createdAt", mindMap.getCreatedAt() != null ? mindMap.getCreatedAt().toString() : null);
        return data;
    }
}
