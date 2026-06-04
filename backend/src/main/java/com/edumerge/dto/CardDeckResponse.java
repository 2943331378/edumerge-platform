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
public class CardDeckResponse {
    private Long id;
    private Long docId;
    private String title;
    private String type;
    private LocalDateTime createdAt;
}
