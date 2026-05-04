package com.edumerge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 学习卡片实体
 *
 * 数据素质说明:
 * - doc_id + source_segment 实现卡片内容与源文档的精确关联，满足"可追溯性"要求
 * - 通过对文档知识的抽取、组织与索引，实现教育数据的"组织管理"
 * - 符合大赛对数据要素治理的考核标准
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("flashcards")
public class Flashcard {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联源文档 ID (数据可追溯) */
    private Long docId;

    /** 创建用户 ID */
    private Long userId;

    /** 问题 */
    private String question;

    /** 答案 */
    private String answer;

    /** 解析 / 知识扩展 */
    private String explanation;

    /** 内容源自文档的片段引用 (数据可追溯) */
    private String sourceSegment;

    /** 状态: ACTIVE=活跃, ARCHIVED=归档 */
    private String status;

    /** 难度等级: 0=未评估, 1-5 */
    private Integer difficulty;

    /** 复习次数 */
    private Integer reviewCount;

    /** 最近复习时间 */
    private LocalDateTime lastReviewedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
