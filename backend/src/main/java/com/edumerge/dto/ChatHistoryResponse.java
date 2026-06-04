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
public class ChatHistoryResponse {
    private Long id;
    private String sessionId;
    private String query;
    private String response;
    private Integer retrievedDocuments;
    private Double confidence;
    private Integer isHelpful;
    private String activityType;
    private LocalDateTime createdAt;
}
