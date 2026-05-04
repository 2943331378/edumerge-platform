package com.edumerge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 测试题实体
 *
 * 数据素质说明:
 * - doc_id + source_segment 实现题目与源文档的精确关联，满足"可追溯性"要求
 * - options 字段采用 JSON 格式存储，确保结构化数据的灵活性与"组织管理"
 * - 符合大赛对数据要素治理的考核标准
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("quizzes")
public class Quiz {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联源文档 ID (数据可追溯) */
    private Long docId;

    /** 创建用户 ID */
    private Long userId;

    /** 问题 */
    private String question;

    /** 选项列表 (JSON 格式, 如: ["A. ...", "B. ..."]) */
    private String options;

    /** 正确答案 */
    private String answer;

    /** 解析 / 知识扩展 */
    private String explanation;

    /** 题目源自文档的片段引用 (数据可追溯) */
    private String sourceSegment;

    /** 题型: SINGLE=单选, MULTIPLE=多选, JUDGE=判断 */
    private String quizType;

    /** 难度等级: 0=未评估, 1-5 */
    private Integer difficulty;

    /** 状态: ACTIVE=活跃, ARCHIVED=归档 */
    private String status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
