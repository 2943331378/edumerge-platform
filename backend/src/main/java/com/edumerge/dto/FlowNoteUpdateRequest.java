package com.edumerge.dto;

import lombok.Data;

@Data
public class FlowNoteUpdateRequest {
    private String category;
    private String title;
    private String content;
    private String sourceSegment;
}
