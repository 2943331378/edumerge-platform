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
@TableName("concept_relationships")
public class ConceptRelationship {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("concept_id_a")
    private Long conceptIdA;

    @TableField("concept_id_b")
    private Long conceptIdB;

    private String relationshipType;
    private String description;
    private Double strength;

    private LocalDateTime createdAt;
}
