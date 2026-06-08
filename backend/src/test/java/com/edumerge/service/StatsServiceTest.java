package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.dto.StatsResponse;
import com.edumerge.entity.*;
import com.edumerge.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * StatsService 单元测试
 * 验证数据资产指标计算和报告生成
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock private DocumentMapper documentMapper;
    @Mock private DocumentChunkMapper documentChunkMapper;
    @Mock private CardDeckMapper cardDeckMapper;
    @Mock private MindMapMapper mindMapMapper;
    @Mock private StudyNoteMapper studyNoteMapper;
    @Mock private FlashcardMapper flashcardMapper;
    @Mock private QuizMapper quizMapper;
    @Mock private ChatHistoryMapper chatHistoryMapper;
    @Mock private FlashcardReviewLogMapper flashcardReviewLogMapper;
    @Mock private QuizAttemptMapper quizAttemptMapper;

    @InjectMocks
    private StatsService statsService;

    @BeforeEach
    void setUp() {
        // 注入 ObjectMapper (通过反射或构造器)
        try {
            var field = StatsService.class.getDeclaredField("objectMapper");
            field.setAccessible(true);
            field.set(statsService, new ObjectMapper());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void calculate_withDocuments_returnsCorrectMetrics() {
        // Mock: 3 completed docs, 10 chunks with content
        when(documentMapper.selectCount(any())).thenReturn(3L);

        DocumentChunk chunk1 = DocumentChunk.builder().content("Hello world").build();
        DocumentChunk chunk2 = DocumentChunk.builder().content("RAG retrieval augmented generation").build();
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(chunk1, chunk2));
        when(documentChunkMapper.selectCount(any())).thenReturn(10L);

        when(cardDeckMapper.selectCount(any())).thenReturn(5L);
        when(mindMapMapper.selectCount(any())).thenReturn(3L);
        when(studyNoteMapper.selectCount(any())).thenReturn(3L);
        when(flashcardMapper.selectCount(any())).thenReturn(50L);
        when(quizMapper.selectCount(any())).thenReturn(20L);
        when(chatHistoryMapper.selectCount(any())).thenReturn(100L);

        StatsResponse result = statsService.calculate();

        assertNotNull(result);
        assertNotNull(result.getDataAssetMetrics());
        assertEquals(3L, result.getDataAssetMetrics().getTotalDocuments());
        assertEquals(50L, result.getDataAssetMetrics().getTotalFlashcards());
        assertEquals(20L, result.getDataAssetMetrics().getTotalQuizzes());
        assertEquals(100L, result.getDataAssetMetrics().getTotalChatExchanges());
        assertTrue(result.getDataAssetMetrics().getTotalCharsProcessed() > 0);
        assertTrue(result.getDataAssetMetrics().getAvgChunksPerDocument() > 0);
        assertTrue(result.getDataAssetMetrics().getVectorCoverageRate() > 0);
    }

    @Test
    void calculate_emptyDatabase_returnsZeros() {
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(documentChunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentChunkMapper.selectCount(any())).thenReturn(0L);
        when(cardDeckMapper.selectCount(any())).thenReturn(0L);
        when(mindMapMapper.selectCount(any())).thenReturn(0L);
        when(studyNoteMapper.selectCount(any())).thenReturn(0L);
        when(flashcardMapper.selectCount(any())).thenReturn(0L);
        when(quizMapper.selectCount(any())).thenReturn(0L);
        when(chatHistoryMapper.selectCount(any())).thenReturn(0L);

        StatsResponse result = statsService.calculate();

        assertEquals(0L, result.getDataAssetMetrics().getTotalDocuments());
        assertEquals(0.0, result.getDataAssetMetrics().getAvgChunksPerDocument());
        assertEquals(0.0, result.getDataAssetMetrics().getVectorCoverageRate());
    }

    @Test
    void calculate_efficiencyMetrics_present() {
        when(documentMapper.selectCount(any())).thenReturn(1L);
        when(documentChunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentChunkMapper.selectCount(any())).thenReturn(0L);
        when(cardDeckMapper.selectCount(any())).thenReturn(0L);
        when(mindMapMapper.selectCount(any())).thenReturn(0L);
        when(studyNoteMapper.selectCount(any())).thenReturn(0L);
        when(flashcardMapper.selectCount(any())).thenReturn(0L);
        when(quizMapper.selectCount(any())).thenReturn(0L);
        when(chatHistoryMapper.selectCount(any())).thenReturn(0L);

        StatsResponse result = statsService.calculate();

        assertNotNull(result.getEfficiencyMetrics());
        assertEquals("67%", result.getEfficiencyMetrics().getEstimatedPrepTimeReduction());
        assertEquals("48%", result.getEfficiencyMetrics().getEstimatedLearningEfficiencyGain());
    }

    @Test
    void calculate_governanceMetrics_present() {
        when(documentMapper.selectCount(any())).thenReturn(1L);
        when(documentChunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentChunkMapper.selectCount(any())).thenReturn(0L);
        when(cardDeckMapper.selectCount(any())).thenReturn(0L);
        when(mindMapMapper.selectCount(any())).thenReturn(0L);
        when(studyNoteMapper.selectCount(any())).thenReturn(0L);
        when(flashcardMapper.selectCount(any())).thenReturn(0L);
        when(quizMapper.selectCount(any())).thenReturn(0L);
        when(chatHistoryMapper.selectCount(any())).thenReturn(1L);

        StatsResponse result = statsService.calculate();

        assertNotNull(result.getGovernanceMetrics());
        assertEquals(0.98, result.getGovernanceMetrics().getAuditPassRate(), 0.01);
        assertEquals(1.0, result.getGovernanceMetrics().getTraceableResponseRate(), 0.01);
    }

    @Test
    void generateReport_containsMarkdownHeaders() {
        when(documentMapper.selectCount(any())).thenReturn(1L);
        when(documentChunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentChunkMapper.selectCount(any())).thenReturn(0L);
        when(cardDeckMapper.selectCount(any())).thenReturn(0L);
        when(mindMapMapper.selectCount(any())).thenReturn(0L);
        when(studyNoteMapper.selectCount(any())).thenReturn(0L);
        when(flashcardMapper.selectCount(any())).thenReturn(0L);
        when(quizMapper.selectCount(any())).thenReturn(0L);
        when(chatHistoryMapper.selectCount(any())).thenReturn(0L);

        String report = statsService.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("# EduMerge 数据素质自评报告"));
        assertTrue(report.contains("## 二、数据治理能力"));
        assertTrue(report.contains("## 三、AI 质量评测"));
        assertTrue(report.contains("## 五、合规审计摘要"));
    }

    @Test
    void updateEvalMetrics_storesInMemory() {
        statsService.updateEvalMetrics(0.85, 4.2, 4.5, 0.88, 10);

        // 验证下一次 calculate 能拿到评测指标
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(documentChunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentChunkMapper.selectCount(any())).thenReturn(0L);
        when(cardDeckMapper.selectCount(any())).thenReturn(0L);
        when(mindMapMapper.selectCount(any())).thenReturn(0L);
        when(studyNoteMapper.selectCount(any())).thenReturn(0L);
        when(flashcardMapper.selectCount(any())).thenReturn(0L);
        when(quizMapper.selectCount(any())).thenReturn(0L);
        when(chatHistoryMapper.selectCount(any())).thenReturn(0L);

        StatsResponse result = statsService.calculate();

        assertNotNull(result.getEvalMetrics());
        assertEquals(0.85, result.getEvalMetrics().getHitRate(), 0.01);
        assertEquals(4.2, result.getEvalMetrics().getAvgFaithfulness(), 0.01);
        assertEquals(10, result.getEvalMetrics().getTotalQuestions());
    }
}
