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
public class DocumentResponse {
    private Long id;
    private String documentId;
    private String title;
    private String description;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String status;
    private String statusMessage;
    private Integer chunkCount;
    private Integer vectorCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
