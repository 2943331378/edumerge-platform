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
@TableName("card_decks")
public class CardDeck {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;
    private String title;
    private String type; // FLASHCARD / QUIZ / MIND_MAP / NOTE

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
