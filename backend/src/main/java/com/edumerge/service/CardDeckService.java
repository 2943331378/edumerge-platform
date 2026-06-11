package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.CardDeck;
import com.edumerge.mapper.CardDeckMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class CardDeckService {

    private final CardDeckMapper cardDeckMapper;

    @Autowired
    public CardDeckService(CardDeckMapper cardDeckMapper) {
        this.cardDeckMapper = cardDeckMapper;
    }

    /** 创建卡片组 (默认标题) */
    @Transactional
    public CardDeck create(Long docId, String type) {
        String suffix = switch (type) {
            case "FLASHCARD" -> "核心概念提取";
            case "QUIZ" -> "测试题生成";
            case "MIND_MAP" -> "思维导图";
            case "NOTE" -> "学习笔记";
            default -> type;
        };
        String title = LocalDateTime.now().format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) + " " + suffix;
        return createWithTitle(docId, type, title);
    }

    /** 创建卡片组 (自定义标题) */
    @Transactional
    public CardDeck create(Long docId, String type, String customTitle) {
        String title = (customTitle != null && !customTitle.isBlank()) ? customTitle.trim() : null;
        if (title == null) return create(docId, type);
        return createWithTitle(docId, type, title);
    }

    private CardDeck createWithTitle(Long docId, String type, String title) {
        CardDeck deck = CardDeck.builder()
                .docId(docId).title(title).type(type).createdAt(LocalDateTime.now()).build();
        cardDeckMapper.insert(deck);
        log.info("卡片组已创建: id={}, docId={}, type={}, title={}", deck.getId(), docId, type, deck.getTitle());
        return deck;
    }

    /** 按文档和类型列出卡片组 */
    @Transactional(readOnly = true)
    public List<CardDeck> listByDocIdAndType(Long docId, String type) {
        return cardDeckMapper.selectList(
                new LambdaQueryWrapper<CardDeck>()
                        .eq(docId != null, CardDeck::getDocId, docId)
                        .eq(type != null, CardDeck::getType, type)
                        .orderByDesc(CardDeck::getCreatedAt));
    }

    /** 按 ID 查询 */
    @Transactional(readOnly = true)
    public CardDeck getById(Long id) {
        return cardDeckMapper.selectById(id);
    }

    /** 删除卡片组 */
    @Transactional
    @CacheEvict(cacheNames = {"dashboard", "stats"}, allEntries = true)
    public void delete(Long id) {
        cardDeckMapper.deleteById(id);
        log.info("卡片组已删除: id={}", id);
    }
}
