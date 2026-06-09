package com.edumerge.dto;

import lombok.Data;

/**
 * 共用的 AI 内容生成请求 — 闪卡 / 测验
 */
@Data
public class GenerateRequest {

    private String docId;
    private String docUuid;
    private String sessionId;
    private String sectionContext;
    private String startChunk;
    private String endChunk;
}
