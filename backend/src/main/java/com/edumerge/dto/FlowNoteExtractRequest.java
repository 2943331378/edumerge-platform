package com.edumerge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FlowNoteExtractRequest {

    private Long docId;
    private String sessionId;
    /** 从最近多少轮对话中提取，默认10 */
    private Integer maxExchanges;
}
