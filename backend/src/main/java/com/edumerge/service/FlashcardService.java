package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.Flashcard;
import com.edumerge.mapper.FlashcardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 学习卡片 CRUD 服务 (AI 生成逻辑已抽离至 ai.AiFlashcardGenerator)
 */
@Slf4j
@Service
public class FlashcardService {

    private final FlashcardMapper flashcardMapper;

    @Autowired
    public FlashcardService(FlashcardMapper flashcardMapper) {
        this.flashcardMapper = flashcardMapper;
    }

    public List<Flashcard> listByDocId(Long docId) {
        return flashcardMapper.selectList(
                new LambdaQueryWrapper<Flashcard>()
                        .eq(Flashcard::getDocId, docId)
                        .orderByAsc(Flashcard::getId));
    }

    public Flashcard create(Flashcard card) {
        flashcardMapper.insert(card);
        log.info("学习卡片已创建: id={}, docId={}", card.getId(), card.getDocId());
        return card;
    }

    public void batchCreate(List<Flashcard> cards) {
        if (!cards.isEmpty()) {
            flashcardMapper.insert(cards, 50);
            log.info("批量创建学习卡片完成: 数量={}", cards.size());
        }
    }

    public List<Flashcard> listByDeckId(Long deckId) {
        return flashcardMapper.selectList(
                new LambdaQueryWrapper<Flashcard>()
                        .eq(Flashcard::getDeckId, deckId)
                        .orderByAsc(Flashcard::getId));
    }

    public void updateById(Flashcard card) {
        flashcardMapper.updateById(card);
        log.info("学习卡片已更新: id={}", card.getId());
    }

    public int deleteById(Long id) {
        int rows = flashcardMapper.deleteById(id);
        if (rows > 0) log.info("学习卡片已删除: id={}", id);
        return rows;
    }
}
