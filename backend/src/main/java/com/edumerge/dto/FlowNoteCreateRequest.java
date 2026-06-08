package com.edumerge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FlowNoteCreateRequest {
    @NotNull(message = "docId 不能为空")
    private Long docId;

    @NotBlank(message = "category 不能为空")
    private String category;

    @NotBlank(message = "title 不能为空")
    private String title;

    @NotBlank(message = "content 不能为空")
    private String content;

    private String sessionId;
}
