package com.edumerge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("quiz_attempts")
public class QuizAttempt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long docId;
    private Long deckId;

    private Integer totalQuestions;
    private Integer correctCount;
    private Double scorePercent;

    /** JSON: [{quizId, selectedAnswer, correct}] */
    private String answerDetails;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
}
