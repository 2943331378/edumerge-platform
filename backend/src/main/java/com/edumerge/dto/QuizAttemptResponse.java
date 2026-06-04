package com.edumerge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuizAttemptResponse {
    private Long id;
    private Long docId;
    private Long deckId;
    private Integer totalQuestions;
    private Integer correctCount;
    private Double scorePercent;
    private String answerDetails;
    private LocalDateTime createdAt;
}
