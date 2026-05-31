package com.edumerge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据资产看板响应 DTO — 2026 大数据要素素质大赛
 * 将数据治理成果量化为可展示的指标体系
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StatsResponse {

    /** 数据资产指标 — 体现非结构化数据向生产要素的转化规模 */
    private DataAssetMetrics dataAssetMetrics;

    /** 效率提升指标 — 体现 AI 辅助教育的生产价值 */
    private EfficiencyMetrics efficiencyMetrics;

    /** 治理合规指标 — 体现数据安全与可追溯性 */
    private GovernanceMetrics governanceMetrics;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DataAssetMetrics {
        /** 累计处理文档数 */
        private long totalDocuments;
        /** 累计处理非结构化数据字数 (字符数) */
        private long totalCharsProcessed;
        /** 生成的结构化卡片组 (Deck) 数 */
        private long totalDecks;
        /** 生成的思维导图数 */
        private long totalMindMaps;
        /** 生成的学习笔记数 */
        private long totalStudyNotes;
        /** 生成的闪卡数 */
        private long totalFlashcards;
        /** 生成的测验题数 */
        private long totalQuizzes;
        /** AI 对话交换次数 */
        private long totalChatExchanges;
        /** 平均每文档切片数 */
        private double avgChunksPerDocument;
        /** 向量覆盖率 (已向量化的切片比例) */
        private double vectorCoverageRate;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EfficiencyMetrics {
        /** 预估备课时间缩减比例 (对比传统模式) */
        private String estimatedPrepTimeReduction;
        /** 预估学习效率提升比例 */
        private String estimatedLearningEfficiencyGain;
        /** 数据到知识资产的转化率描述 */
        private String dataToAssetConversionRate;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class GovernanceMetrics {
        /** 审计通过率 */
        private double auditPassRate;
        /** 累计审计日志数 */
        private long totalAuditLogs;
        /** 可溯源回答比例 */
        private double traceableResponseRate;
    }
}
