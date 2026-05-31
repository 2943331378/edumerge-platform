package com.edumerge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("study_notes")
public class StudyNote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;
    private Long deckId;
    private String title;
    private String content;
    private String sourceSummary;
    private String requirements;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
