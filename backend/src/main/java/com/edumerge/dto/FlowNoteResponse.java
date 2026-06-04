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
public class FlowNoteResponse {
    private Long id;
    private Long docId;
    private String sessionId;
    private String category;
    private String title;
    private String content;
    private String sourceSegment;
    private String sourceType;
    private Integer isReviewed;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
