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
@TableName("concept_documents")
public class ConceptDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conceptId;
    private Long docId;
    private String docUuid;
    private Integer chunkIndex;
    private String mentionText;
    private Double relevanceScore;

    private LocalDateTime createdAt;
}
