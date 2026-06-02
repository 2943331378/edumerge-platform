package com.edumerge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档大纲响应 DTO — 将 outlineJson 字符串解析为结构化对象返回给前端
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OutlineResponse {

    private Long id;
    private Long docId;
    private String docType;
    private String docTypeLabel;
    private Object outline;  // 解析后的 JSON 对象 (OutlineData)
    private Integer version;
    private LocalDateTime createdAt;

    /**
     * 从 DocumentOutline 实体构造 DTO
     * @param entity 大纲实体
     * @param objectMapper Jackson ObjectMapper (用于解析 outlineJson)
     */
    public static OutlineResponse from(com.edumerge.entity.DocumentOutline entity,
                                        com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        OutlineResponse resp = new OutlineResponse();
        resp.setId(entity.getId());
        resp.setDocId(entity.getDocId());
        resp.setDocType(entity.getDocType());
        resp.setDocTypeLabel(entity.getDocTypeLabel());
        resp.setVersion(entity.getVersion());
        resp.setCreatedAt(entity.getCreatedAt());
        try {
            resp.setOutline(objectMapper.readValue(entity.getOutlineJson(), Object.class));
        } catch (Exception e) {
            // JSON 解析失败时返回原始字符串
            resp.setOutline(entity.getOutlineJson());
        }
        return resp;
    }
}
