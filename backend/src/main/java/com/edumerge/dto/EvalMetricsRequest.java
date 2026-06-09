package com.edumerge.dto;

import lombok.Data;

/**
 * RAG 评测指标推送请求体 — 由 evaluate_rag.py 调用
 */
@Data
public class EvalMetricsRequest {

    private Double hitRate;
    private Double avgFaithfulness;
    private Double avgCorrectness;
    private Double compositeScore;
    private Integer totalQuestions;
}
