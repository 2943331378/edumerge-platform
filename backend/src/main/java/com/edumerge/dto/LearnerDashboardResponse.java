package com.edumerge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 学习者个人中心看板 DTO
 * 聚合学习者真正关心的数据：待办、进度、成就
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LearnerDashboardResponse {

    /** 今日待办 */
    private TodayTasks today;

    /** 学习节奏 */
    private LearningRhythm rhythm;

    /** 累计成就 */
    private Achievement achievement;

    /** 待复习文档列表（有到期闪卡的文档） */
    private List<DocDueInfo> dueDocs;

    /** 薄弱知识点：错误次数最多的题目 Top N */
    private List<ErrorItem> topErrors;

    /** 薄弱知识点：按文档统计正确率（从低到高） */
    private List<DeckWeakness> deckWeaknesses;

    /** 文档学习进度（按文档维度） */
    private List<DocProgress> docProgress;

    /** 今日学习时间线 */
    private List<TimelineEntry> todayTimeline;

    /** 周度学习报告 */
    private WeeklySummary weeklySummary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TodayTasks {
        /** 今日已复习卡片数 */
        private long reviewedCards;
        /** 今日已做测验次数 */
        private long quizAttempts;
        /** 今日测验正确率 (0-100) */
        private double quizAccuracy;
        /** 今日答对/总答 */
        private long correctCount;
        private long totalAnswered;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class LearningRhythm {
        /** 当前连续学习天数 */
        private int streakDays;
        /** 近 7 天每日复习数 */
        private List<DailyActivity> weekly;
        /** 近 30 天每日活动（热力图用） */
        private List<DailyActivity> monthly;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DailyActivity {
        /** 日期 (MM-dd) */
        private String date;
        /** 当日复习卡片数 */
        private long reviews;
        /** 当日测验数 */
        private long quizzes;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Achievement {
        /** 累计复习卡片数 */
        private long totalReviews;
        /** 累计测验次数 */
        private long totalQuizzes;
        /** 累计测验平均正确率 (0-100) */
        private double avgAccuracy;
        /** 学习材料数 */
        private long totalDocs;
        /** 生成的闪卡总数 */
        private long totalFlashcards;
        /** 生成的测验题总数 */
        private long totalQuizQuestions;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DocDueInfo {
        private long docId;
        private String docName;
        private long dueCount;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ErrorItem {
        private long quizId;
        private String question;
        private String answer;
        private String explanation;
        private int errorCount;
        private long docId;
        private String docName;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DeckWeakness {
        private long docId;
        private String docName;
        private long totalQuestions;
        private long correctCount;
        /** 正确率 0-100 */
        private double accuracyRate;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DocProgress {
        private long docId;
        private String docName;
        /** 闪卡总数 */
        private long totalCards;
        /** 已复习过的闪卡数（至少 review 过一次） */
        private long reviewedCards;
        /** 测验正确率 0-100（无测验数据时为 -1） */
        private double quizAccuracy;
        /** 测验答题总题数 */
        private long quizTotal;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TimelineEntry {
        /** 时间 (HH:mm) */
        private String time;
        /** 类型: "review" | "quiz" */
        private String type;
        /** 描述（如 "复习了 8 张卡片"） */
        private String description;
        /** 文档名 */
        private String docName;
        /** 关联的 docId */
        private long docId;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WeeklySummary {
        /** 本周复习卡片数 */
        private long reviews;
        /** 本周测验次数 */
        private long quizzes;
        /** 本周测验正确率 (0-100) */
        private double accuracy;
        /** 本周活跃天数 */
        private int activeDays;
        /** 本周学习最多的文档名 */
        private String topDocName;
        /** 本周学习最多的文档复习数 */
        private long topDocReviews;
        /** 上周复习卡片数（对比用） */
        private long prevReviews;
        /** 上周测验次数 */
        private long prevQuizzes;
        /** 上周测验正确率 */
        private double prevAccuracy;
    }
}
