package com.edumerge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 个人学习行为统计 DTO
 * 来源: flashcard_review_logs + quiz_attempts
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LearningStatsResponse {

    /** 今日学习概况 */
    private TodayStats today;

    /** 近 7 天每日统计 */
    private List<DailyStats> weekly;

    /** 累计学习概况 */
    private AllTimeStats allTime;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TodayStats {
        /** 今日复习卡片数 */
        private long flashcardReviews;
        /** 今日测验次数 */
        private long quizAttempts;
        /** 今日测验正确率 (0-100) */
        private double quizAccuracy;
        /** 今日测验答题总数 */
        private long totalQuestionsAnswered;
        /** 今日测验答对数 */
        private long totalCorrect;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DailyStats {
        /** 日期 (yyyy-MM-dd) */
        private String date;
        /** 当日复习卡片数 */
        private long flashcardReviews;
        /** 当日测验次数 */
        private long quizAttempts;
        /** 当日测验正确率 (0-100) */
        private double quizAccuracy;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AllTimeStats {
        /** 累计复习卡片数 */
        private long totalFlashcardReviews;
        /** 累计测验次数 */
        private long totalQuizAttempts;
        /** 累计测验平均正确率 (0-100) */
        private double avgQuizAccuracy;
        /** 最长连续学习天数 */
        private int streakDays;
    }
}
