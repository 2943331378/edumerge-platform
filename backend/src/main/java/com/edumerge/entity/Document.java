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
@TableName("documents")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("document_id")
    private String documentId;

    private Long userId;
    private String title;
    private String description;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String filePath;
    private String status;
    private String statusMessage;
    private Integer chunkCount;
    private Integer vectorCount;
    private Integer pageCount;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
