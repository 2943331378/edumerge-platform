package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.Flashcard;
import com.edumerge.mapper.FlashcardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.edumerge.entity.FlashcardReviewLog;
import com.edumerge.mapper.FlashcardReviewLogMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习卡片 CRUD 服务 (AI 生成逻辑已抽离至 ai.AiFlashcardGenerator)
 */
@Slf4j
@Service
public class FlashcardService {

    private final FlashcardMapper flashcardMapper;
    private final FlashcardReviewLogMapper reviewLogMapper;

    @Autowired
    public FlashcardService(FlashcardMapper flashcardMapper, FlashcardReviewLogMapper reviewLogMapper) {
        this.flashcardMapper = flashcardMapper;
        this.reviewLogMapper = reviewLogMapper;
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

    public Flashcard getById(Long id) {
        return flashcardMapper.selectById(id);
    }

    /**
     * SM-2 间隔重复: 执行自评并更新卡片复习参数
     *
     * @param cardId 卡片 ID
     * @param quality 自评分数: 1=忘了 2=模糊 3=记住 4=秒答
     * @param userId 当前用户 ID（用于记录复习日志）
     * @return 更新后的卡片
     */
    public Flashcard review(Long cardId, int quality, Long userId) {
        Flashcard card = getById(cardId);
        if (card == null) throw new IllegalArgumentException("卡片不存在: " + cardId);

        double oldEF = card.getEaseFactor() != null ? card.getEaseFactor() : 2.5;
        int oldInterval = card.getReviewInterval() != null ? card.getReviewInterval() : 0;

        // SM-2 公式: EF' = max(1.3, EF + (0.1 - (5-q)*(0.08 + (5-q)*0.02)))
        double newEF = Math.max(1.3, oldEF + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)));

        int newInterval;
        if (quality < 3) {
            newInterval = 1; // 忘了/模糊: 1 天后重来
        } else if (oldInterval == 0) {
            newInterval = 1;
        } else if (oldInterval == 1) {
            newInterval = 6;
        } else {
            newInterval = (int) Math.round(oldInterval * newEF);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReview = now.plusDays(newInterval);

        // 更新卡片
        card.setEaseFactor(newEF);
        card.setReviewInterval(newInterval);
        card.setNextReviewAt(nextReview);
        card.setReviewCount((card.getReviewCount() != null ? card.getReviewCount() : 0) + 1);
        card.setLastReviewedAt(now);
        updateById(card);

        // 记录复习日志
        FlashcardReviewLog reviewLog = FlashcardReviewLog.builder()
                .userId(userId)
                .flashcardId(cardId)
                .quality(quality)
                .easeFactor(newEF)
                .reviewInterval(newInterval)
                .nextReviewAt(nextReview)
                .build();
        reviewLogMapper.insert(reviewLog);

        log.info("SM-2 复习完成: cardId={}, quality={}, newEF={}, newInterval={}天, nextReview={}",
                cardId, quality, String.format("%.2f", newEF), newInterval, nextReview);
        return card;
    }

    /**
     * 查询到期需复习的卡片:
     * - nextReviewAt <= now (已到复习时间)
     * - 或 nextReviewAt 为 null 且 reviewCount == 0 (新卡片从未复习过)
     */
    public List<Flashcard> listDueCards(Long docId) {
        LocalDateTime now = LocalDateTime.now();
        return flashcardMapper.selectList(
                new LambdaQueryWrapper<Flashcard>()
                        .eq(Flashcard::getDocId, docId)
                        .eq(Flashcard::getStatus, "ACTIVE")
                        .and(w -> w
                                .le(Flashcard::getNextReviewAt, now)
                                .or()
                                .isNull(Flashcard::getNextReviewAt)
                        )
                        .orderByAsc(Flashcard::getNextReviewAt));
    }
}
