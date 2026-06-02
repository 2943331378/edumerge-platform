package com.edumerge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 闪卡复习记录 (SM-2 算法日志)
 *
 * 每次用户自评后记录: 自评分数 → 新的 easeFactor / interval / nextReviewAt
 * 用于错题分析、复习统计、以及 SM-2 参数回溯
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("flashcard_review_logs")
public class FlashcardReviewLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 关联卡片 ID */
    private Long flashcardId;

    /** 自评分数: 1=忘了 2=模糊 3=记住 4=秒答 */
    private Integer quality;

    /** 本次复习后的简易因子 */
    private Double easeFactor;

    /** 本次复习后的间隔(天) */
    private Integer reviewInterval;

    /** 下次复习时间 */
    private LocalDateTime nextReviewAt;

    private LocalDateTime createdAt;
}
