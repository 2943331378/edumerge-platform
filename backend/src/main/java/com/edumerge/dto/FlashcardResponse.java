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
public class FlashcardResponse {
    private Long id;
    private Long docId;
    private Long deckId;
    private String question;
    private String answer;
    private String explanation;
    private String sourceSegment;
    private String status;
    private Integer difficulty;
    private Integer reviewCount;
    private LocalDateTime lastReviewedAt;
    private Double easeFactor;
    private Integer reviewInterval;
    private LocalDateTime nextReviewAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
