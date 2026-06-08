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
@TableName("document_folders")
public class DocumentFolder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String name;
    private String color;
    private Long parentId;
    private Integer sortOrder;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
