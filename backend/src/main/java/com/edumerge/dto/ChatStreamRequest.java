package com.edumerge.dto;

import lombok.Data;

/**
 * 流式 RAG 对话请求体
 */
@Data
public class ChatStreamRequest {

    private String message;
    private String documentId;
    private String sessionId;
    private String docId;
    private String activityType;
    private String contextHint;
}
