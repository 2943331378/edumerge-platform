package com.edumerge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {
    @NotBlank(message = "消息不能为空")
    private String message;

    private String documentId;
    private String sessionId;
    private String docId;
    private String activityType;
    private String contextHint;
}
