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
@TableName("user_mastered_quizzes")
public class MasteredQuiz {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long quizId;

    private LocalDateTime createdAt;
}
