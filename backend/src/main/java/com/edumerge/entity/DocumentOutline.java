package com.edumerge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档大纲实体 — 存储 AI 识别的文档类型与章节结构
 *
 * 数据素质说明:
 * - doc_id 关联源文档, outline_json 存储结构化章节树, 实现"非结构化文档 → 结构化目录"的转化
 * - version 字段支持用户编辑后版本递增, 保障数据可追溯性
 * - doc_type 实现文档分类治理, 符合数据要素组织管理要求
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("document_outlines")
public class DocumentOutline {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联文档 ID */
    private Long docId;

    /** 创建用户 ID */
    private Long userId;

    /** 文档类型: TEXTBOOK/PAPER/NOTE/SLIDE/MANUAL/OTHER */
    private String docType;

    /** 文档类型中文标签 */
    private String docTypeLabel;

    /** 大纲 JSON (树状结构, 含 sections + chunk 范围映射) */
    private String outlineJson;

    /** 版本号 (用户编辑后递增) */
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
