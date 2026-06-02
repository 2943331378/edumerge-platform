package com.edumerge.controller;

import com.edumerge.ai.AiMindMapGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.CardDeck;
import com.edumerge.entity.Document;
import com.edumerge.entity.MindMap;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.CardDeckService;
import com.edumerge.service.DocumentService;
import com.edumerge.service.MindMapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 查询文档的所有思维导图列表 */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> listMindMaps(@RequestParam Long docId) {
        List<CardDeck> decks = cardDeckService.listByDocIdAndType(docId, "MIND_MAP");
        List<Map<String, Object>> result = new ArrayList<>();
        for (CardDeck deck : decks) {
            MindMap mm = mindMapService.getByDeckId(deck.getId());
            if (mm != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("deckId", mm.getDeckId());
                item.put("docId", mm.getDocId());
                item.put("title", deck.getTitle());
                item.put("createdAt", deck.getCreatedAt() != null ? deck.getCreatedAt().toString() : null);
                result.add(item);
            }
        }
        return Result.success(result);
    }

    /** 获取指定 deckId 的思维导图内容 */
    @GetMapping("/detail")
    public Result<Map<String, Object>> getMindMapDetail(@RequestParam Long deckId) {
        MindMap mm = mindMapService.getByDeckId(deckId);
        if (mm == null) return Result.fail("思维导图不存在");
        CardDeck deck = cardDeckService.getById(mm.getDeckId());
        return Result.success(toMap(mm, deck));
    }

    /** 生成新的思维导图 (支持章节上下文) */
    @PostMapping("/generate")
    public Result<Map<String, Object>> generateMindMap(@RequestParam Long docId,
                                                       @RequestParam(required = false) String sectionContext) {
        Document doc = documentService.getById(docId);
        if (doc == null) return Result.fail("文档不存在: " + docId);
        String docUuid = doc.getDocumentId();
        if (docUuid == null || docUuid.isBlank()) {
            return Result.fail("文档尚未完成向量化, 无法生成思维导图");
        }

        log.info("开始生成思维导图: docId={}, sectionContext={}", docId,
                sectionContext != null ? sectionContext.substring(0, Math.min(100, sectionContext.length())) : "null");
        AiMindMapGenerator.MindMapResult genResult = aiMindMapGenerator.generate(
                docId, SecurityUtils.getCurrentUserId(), docUuid, sectionContext);

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

    /** 删除指定思维导图 */
    @DeleteMapping("/{deckId}")
    public Result<Void> deleteMindMap(@PathVariable Long deckId) {
        mindMapService.deleteByDeckId(deckId);
        cardDeckService.delete(deckId);
        log.info("思维导图已删除: deckId={}", deckId);
        return Result.success(null);
    }

    /** 兼容旧接口: GET /mindmap?docId= → 返回最新一条或自动生成 */
    @GetMapping
    public Result<Map<String, Object>> getMindMap(@RequestParam Long docId) {
        List<CardDeck> decks = cardDeckService.listByDocIdAndType(docId, "MIND_MAP");
        if (!decks.isEmpty()) {
            MindMap existing = mindMapService.getByDeckId(decks.get(0).getId());
            if (existing != null) {
                return Result.success(toMap(existing, decks.get(0)));
            }
        }
        // 无缓存 → 自动生成
        return generateMindMap(docId, null);
    }

    private Map<String, Object> toMap(MindMap mindMap, CardDeck deck) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", mindMap.getDeckId());
        data.put("docId", mindMap.getDocId());
        data.put("title", deck != null ? deck.getTitle() : "思维导图");
        data.put("content", mindMap.getContent());
        data.put("createdAt", mindMap.getCreatedAt() != null ? mindMap.getCreatedAt().toString() : null);
        return data;
    }
}
