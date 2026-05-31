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
@TableName("flow_notes")
public class FlowNote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long docId;
    private String sessionId;

    /** KEY_POINT | QUESTION | EXAMPLE | REVIEW */
    private String category;

    private String title;
    private String content;
    private String sourceSegment;

    /** AI_GENERATED | USER_WRITTEN | CHAT_EXTRACTED */
    private String sourceType;

    private Long chatHistoryId;
    private Integer isReviewed;
    private LocalDateTime reviewedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
