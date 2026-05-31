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
@TableName("chat_history")
public class ChatHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String sessionId;
    private String query;
    private String response;
    private Integer retrievedDocuments;
    private Double confidence;
    private Integer tokensUsed;
    private Integer isHelpful;

    /** 活动上下文: notes/mindmap/flashcards/quiz/flownote */
    @TableField("activity_type")
    private String activityType;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
