package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.ai.AiMindMapGenerator;
import com.edumerge.entity.CardDeck;
import com.edumerge.entity.Document;
import com.edumerge.entity.MindMap;
import com.edumerge.mapper.MindMapMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 思维导图业务服务 — CRUD + AI 生成 + 列表编排
 */
@Slf4j
@Service
public class MindMapService {

    private final MindMapMapper mindMapMapper;
    private final CardDeckService cardDeckService;
    private final DocumentService documentService;
    private final AiMindMapGenerator aiMindMapGenerator;

    @Autowired
    public MindMapService(MindMapMapper mindMapMapper,
                          CardDeckService cardDeckService,
                          DocumentService documentService,
                          AiMindMapGenerator aiMindMapGenerator) {
        this.mindMapMapper = mindMapMapper;
        this.cardDeckService = cardDeckService;
        this.documentService = documentService;
        this.aiMindMapGenerator = aiMindMapGenerator;
    }

    // ═══════ CRUD ═══════

    @Transactional(readOnly = true)
    public MindMap getByDocId(Long docId) {
        return mindMapMapper.selectOne(
                new LambdaQueryWrapper<MindMap>()
                        .eq(MindMap::getDocId, docId)
                        .orderByDesc(MindMap::getCreatedAt)
                        .last("LIMIT 1"));
    }

    @Transactional(readOnly = true)
    public List<MindMap> listByDocId(Long docId) {
        return mindMapMapper.selectList(
                new LambdaQueryWrapper<MindMap>()
                        .eq(MindMap::getDocId, docId)
                        .orderByDesc(MindMap::getCreatedAt));
    }

    @Transactional(readOnly = true)
    public MindMap getByDeckId(Long deckId) {
        return mindMapMapper.selectOne(
                new LambdaQueryWrapper<MindMap>()
                        .eq(MindMap::getDeckId, deckId));
    }

    @Transactional
    public void deleteByDeckId(Long deckId) {
        mindMapMapper.delete(
                new LambdaQueryWrapper<MindMap>()
                        .eq(MindMap::getDeckId, deckId));
    }

    @Transactional
    public MindMap create(Long docId, Long deckId, String content) {
        MindMap entity = MindMap.builder()
                .docId(docId).deckId(deckId).content(content).build();
        mindMapMapper.insert(entity);
        log.info("思维导图已创建: id={}, docId={}, deckId={}, contentLen={}",
                entity.getId(), docId, deckId, content.length());
        return entity;
    }

    @Transactional
    public void deleteByDocId(Long docId) {
        mindMapMapper.delete(
                new LambdaQueryWrapper<MindMap>()
                        .eq(MindMap::getDocId, docId));
        log.info("旧思维导图已清理: docId={}", docId);
    }

    // ═══════ 业务编排 ═══════

    /** 查询文档的所有思维导图列表（含 deck 标题） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMindMaps(Long docId) {
        List<CardDeck> decks = cardDeckService.listByDocIdAndType(docId, "MIND_MAP");
        List<Map<String, Object>> result = new ArrayList<>();
        for (CardDeck deck : decks) {
            MindMap mm = getByDeckId(deck.getId());
            if (mm != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("deckId", mm.getDeckId());
                item.put("docId", mm.getDocId());
                item.put("title", deck.getTitle());
                item.put("createdAt", deck.getCreatedAt() != null ? deck.getCreatedAt().toString() : null);
                result.add(item);
            }
        }
        return result;
    }

    /** 获取指定 deckId 的思维导图详情 */
    @Transactional(readOnly = true)
    public Map<String, Object> getMindMapDetail(Long deckId) {
        MindMap mm = getByDeckId(deckId);
        if (mm == null) throw new IllegalArgumentException("思维导图不存在");
        CardDeck deck = cardDeckService.getById(mm.getDeckId());
        return toMap(mm, deck);
    }

    /**
     * 生成新的思维导图
     *
     * @return 包含思维导图元数据的 Map
     * @throws IllegalArgumentException 文档不存在或未向量化
     * @throws IllegalStateException    生成失败
     */
    @Transactional
    public Map<String, Object> generate(Long docId, String sectionContext) {
        Document doc = documentService.getById(docId);
        if (doc == null) throw new IllegalArgumentException("文档不存在: " + docId);
        String docUuid = doc.getDocumentId();
        if (docUuid == null || docUuid.isBlank()) {
            throw new IllegalArgumentException("文档尚未完成向量化, 无法生成思维导图");
        }

        log.info("开始生成思维导图: docId={}, sectionContext={}", docId,
                sectionContext != null ? sectionContext.substring(0, Math.min(100, sectionContext.length())) : "null");

        AiMindMapGenerator.MindMapResult genResult = aiMindMapGenerator.generate(
                docId, docUuid, sectionContext);

        if (!genResult.isSuccess()) {
            throw new IllegalStateException("思维导图生成失败: 未从文档中提取到足够的内容");
        }

        // 持久化 — 创建 deck + mind_map 记录
        CardDeck deck = cardDeckService.create(docId, "MIND_MAP", genResult.getTitle());
        MindMap saved = create(docId, deck.getId(), genResult.getContent());
        log.info("思维导图已持久化: docId={}, deckId={}", docId, deck.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", deck.getId());
        data.put("docId", docId);
        data.put("title", deck.getTitle());
        data.put("content", genResult.getContent());
        data.put("createdAt", saved.getCreatedAt() != null ? saved.getCreatedAt().toString() : null);
        return data;
    }

    /** 获取已有思维导图（兼容旧接口：返回最新一条或自动生成） */
    @Transactional
    public Map<String, Object> getOrGenerate(Long docId) {
        List<CardDeck> decks = cardDeckService.listByDocIdAndType(docId, "MIND_MAP");
        if (!decks.isEmpty()) {
            MindMap existing = getByDeckId(decks.get(0).getId());
            if (existing != null) {
                return toMap(existing, decks.get(0));
            }
        }
        return generate(docId, null);
    }

    /** 删除思维导图（同时清理 deck 和 mindmap 记录） */
    @Transactional
    public void deleteMindMap(Long deckId) {
        deleteByDeckId(deckId);
        cardDeckService.delete(deckId);
        log.info("思维导图已删除: deckId={}", deckId);
    }

    // ═══════ 数据转换 ═══════

    public Map<String, Object> toMap(MindMap mindMap, CardDeck deck) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", mindMap.getDeckId());
        data.put("docId", mindMap.getDocId());
        data.put("title", deck != null ? deck.getTitle() : "思维导图");
        data.put("content", mindMap.getContent());
        data.put("createdAt", mindMap.getCreatedAt() != null ? mindMap.getCreatedAt().toString() : null);
        return data;
    }
}
