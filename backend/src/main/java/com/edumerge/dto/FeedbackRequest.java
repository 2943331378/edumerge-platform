package com.edumerge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FeedbackRequest {
    @NotNull(message = "isHelpful 不能为空")
    @Min(value = 0, message = "isHelpful 最小为 0")
    @Max(value = 1, message = "isHelpful 最大为 1")
    private Integer isHelpful;
}
