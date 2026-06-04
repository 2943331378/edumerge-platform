package com.edumerge.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateFlashcardRequest {
    @Size(min = 1, max = 500, message = "问题长度必须在 1-500 之间")
    private String question;

    @Size(min = 1, max = 2000, message = "答案长度必须在 1-2000 之间")
    private String answer;

    private String explanation;
    private String status;
    private Integer difficulty;
}
