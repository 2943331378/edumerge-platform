package com.edumerge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewRequest {
    @NotNull(message = "quality 不能为空")
    @Min(value = 1, message = "quality 最小为 1 (忘了)")
    @Max(value = 4, message = "quality 最大为 4 (秒答)")
    private Integer quality;
}
