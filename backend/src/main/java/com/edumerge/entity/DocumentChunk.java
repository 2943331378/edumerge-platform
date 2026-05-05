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
@TableName("document_chunks")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("document_id")
    private Long documentId;

    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private String embeddingStatus;
    private Long milvusId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
