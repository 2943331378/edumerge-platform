package com.edumerge.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateQuizRequest {
    @Size(min = 1, max = 500, message = "问题长度必须在 1-500 之间")
    private String question;

    private String options;
    private String answer;
    private String explanation;
    private String status;
    private Integer difficulty;
}
