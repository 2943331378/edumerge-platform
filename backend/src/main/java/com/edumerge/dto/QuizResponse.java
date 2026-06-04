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
public class QuizResponse {
    private Long id;
    private Long docId;
    private Long deckId;
    private String question;
    private String options;
    private String answer;
    private String explanation;
    private String sourceSegment;
    private String quizType;
    private Integer difficulty;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
