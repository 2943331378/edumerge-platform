package com.edumerge.service;

import com.edumerge.entity.Document;
import com.edumerge.entity.Flashcard;
import com.edumerge.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FlashcardService SM-2 算法单元测试
 * 验证 EF' 公式、间隔天数计算、状态更新的正确性
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlashcardServiceTest {

    @Mock private FlashcardMapper flashcardMapper;
    @Mock private CardDeckMapper cardDeckMapper;
    @Mock private FlashcardReviewLogMapper reviewLogMapper;
    @Mock private DocumentService documentService;
    @Mock private SessionService sessionService;
    @Mock private com.edumerge.ai.AiFlashcardGenerator aiFlashcardGenerator;

    @InjectMocks
    private FlashcardService flashcardService;

    private Flashcard newCard;
    private Flashcard reviewedCard;

    @BeforeEach
    void setUp() {
        // 设置 SecurityContext 以便 SecurityUtils.getCurrentUserId() 返回 1L
        var authUser = new com.edumerge.security.AuthUser(1L, "testuser");
        var auth = new UsernamePasswordAuthenticationToken(authUser, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Mock documentService.getById 返回匹配 userId 的文档 (verifyOwnership 用)
        Document doc = new Document();
        doc.setUserId(1L);
        when(documentService.getById(any())).thenReturn(doc);

        // 新卡片: EF=2.5, interval=0, 未复习
        newCard = Flashcard.builder()
                .id(1L)
                .userId(1L)
                .docId(100L)
                .question("什么是 RAG?")
                .easeFactor(2.5)
                .reviewInterval(0)
                .reviewCount(0)
                .status("ACTIVE")
                .deleted(0)
                .build();

        // 已复习卡片: EF=2.5, interval=6, 已复习 3 次
        reviewedCard = Flashcard.builder()
                .id(2L)
                .userId(1L)
                .docId(100L)
                .question("解释向量检索")
                .easeFactor(2.5)
                .reviewInterval(6)
                .reviewCount(3)
                .status("ACTIVE")
                .deleted(0)
                .build();
    }

    // ═══════ SM-2 EF' 公式测试 ═══════

    @Test
    void review_quality4_newCard_interval1() {
        // 新卡片首次复习, quality=4(记住) → interval=1, EF 不变
        when(flashcardMapper.selectById(1L)).thenReturn(newCard);
        when(flashcardMapper.updateById(any(Flashcard.class))).thenReturn(1);

        Flashcard result = flashcardService.review(1L, 4, 1L);

        assertEquals(1, result.getReviewInterval());
        assertEquals(2.5, result.getEaseFactor(), 0.01);
        assertEquals(1, result.getReviewCount());
        assertNotNull(result.getNextReviewAt());
        assertNotNull(result.getLastReviewedAt());
    }

    @Test
    void review_quality1_forget_resetsInterval() {
        // quality=1(忘了) → interval=1, EF 下降
        when(flashcardMapper.selectById(2L)).thenReturn(reviewedCard);
        when(flashcardMapper.updateById(any(Flashcard.class))).thenReturn(1);

        Flashcard result = flashcardService.review(2L, 1, 1L);

        assertEquals(1, result.getReviewInterval());
        assertTrue(result.getEaseFactor() < 2.5, "EF 应下降");
        assertTrue(result.getEaseFactor() >= 1.3, "EF 不应低于 1.3");
    }

    @Test
    void review_quality3_good_increasesInterval() {
        // quality=3(记住) + 已复习卡片(interval=6) → interval = round(6 * EF)
        when(flashcardMapper.selectById(2L)).thenReturn(reviewedCard);
        when(flashcardMapper.updateById(any(Flashcard.class))).thenReturn(1);

        Flashcard result = flashcardService.review(2L, 3, 1L);

        // EF' = max(1.3, 2.5 + (0.1 - (5-3)*(0.08 + (5-3)*0.02)))
        //     = max(1.3, 2.5 + (0.1 - 2*(0.08+0.04)))
        //     = max(1.3, 2.5 + (0.1 - 0.24))
        //     = max(1.3, 2.36) = 2.36
        // interval = round(6 * 2.36) = 14
        assertEquals(14, result.getReviewInterval());
        assertEquals(2.36, result.getEaseFactor(), 0.01);
    }

    @Test
    void review_quality5_excellent_higherInterval() {
        // quality=5(秒答) → EF 上升
        when(flashcardMapper.selectById(2L)).thenReturn(reviewedCard);
        when(flashcardMapper.updateById(any(Flashcard.class))).thenReturn(1);

        Flashcard result = flashcardService.review(2L, 5, 1L);

        // EF' = max(1.3, 2.5 + (0.1 - 0*(...))) = max(1.3, 2.6) = 2.6
        // interval = round(6 * 2.6) = 16
        assertEquals(16, result.getReviewInterval());
        assertEquals(2.6, result.getEaseFactor(), 0.01);
    }

    @Test
    void review_quality2_fuzzy_interval1() {
        // quality=2(模糊) → interval=1, EF 下降
        when(flashcardMapper.selectById(2L)).thenReturn(reviewedCard);
        when(flashcardMapper.updateById(any(Flashcard.class))).thenReturn(1);

        Flashcard result = flashcardService.review(2L, 2, 1L);

        assertEquals(1, result.getReviewInterval());
        assertTrue(result.getEaseFactor() < 2.5);
    }

    @Test
    void review_efNeverBelow13() {
        // 连续多次 quality=1, EF 应不低于 1.3
        Flashcard card = Flashcard.builder()
                .id(3L).userId(1L).easeFactor(1.35).reviewInterval(1)
                .reviewCount(10).status("ACTIVE").deleted(0).build();
        when(flashcardMapper.selectById(3L)).thenReturn(card);
        when(flashcardMapper.updateById(any(Flashcard.class))).thenReturn(1);

        Flashcard result = flashcardService.review(3L, 1, 1L);

        assertTrue(result.getEaseFactor() >= 1.3, "EF 下限 1.3");
    }

    // ═══════ 边界条件 ═══════

    @Test
    void review_cardNotFound_throws() {
        when(flashcardMapper.selectById(999L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> flashcardService.review(999L, 3, 1L));
    }

    @Test
    void review_nullEF_defaultsTo25() {
        // EF 为 null 时应默认 2.5
        Flashcard card = Flashcard.builder()
                .id(4L).userId(1L).easeFactor(null).reviewInterval(null)
                .reviewCount(null).status("ACTIVE").deleted(0).build();
        when(flashcardMapper.selectById(4L)).thenReturn(card);
        when(flashcardMapper.updateById(any(Flashcard.class))).thenReturn(1);

        Flashcard result = flashcardService.review(4L, 4, 1L);

        assertEquals(1, result.getReviewInterval());
        assertEquals(2.5, result.getEaseFactor(), 0.01);
    }

    @Test
    void review_intervalFrom1to6_thenBack() {
        // interval=1, quality≥3 → interval=6
        when(flashcardMapper.selectById(1L)).thenReturn(newCard);
        when(flashcardMapper.updateById(any(Flashcard.class))).thenReturn(1);

        Flashcard step1 = flashcardService.review(1L, 4, 1L);
        assertEquals(1, step1.getReviewInterval());

        // 第二次: interval=1 → interval=6
        step1.setId(1L);
        when(flashcardMapper.selectById(1L)).thenReturn(step1);
        Flashcard step2 = flashcardService.review(1L, 4, 1L);
        assertEquals(6, step2.getReviewInterval());
    }
}
