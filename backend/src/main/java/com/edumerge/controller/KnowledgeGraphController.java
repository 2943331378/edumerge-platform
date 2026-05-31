package com.edumerge.controller;

import com.edumerge.ai.AiKnowledgeGraphGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/knowledge-graph")
public class KnowledgeGraphController {

    private final KnowledgeGraphService kgService;

    public KnowledgeGraphController(KnowledgeGraphService kgService) {
        this.kgService = kgService;
    }

    /** 获取已有图谱 */
    @GetMapping
    public Result<Map<String, Object>> getGraph() {
        Long userId = SecurityUtils.getCurrentUserId();
        Map<String, Object> graph = kgService.getGraph(userId);
        if (graph == null) {
            return Result.success("暂无图谱", null);
        }
        return Result.success(graph);
    }

    /** AI 生成图谱 */
    @PostMapping("/generate")
    public Result<Map<String, Object>> generate() {
        Long userId = SecurityUtils.getCurrentUserId();
        AiKnowledgeGraphGenerator.KnowledgeGraphResult genResult = kgService.generate(userId);

        if (!genResult.isSuccess()) {
            return Result.fail(genResult.getMessage());
        }

        Map<String, Object> graph = kgService.getGraph(userId);
        return Result.success(
                "知识图谱生成完成: " + genResult.getConceptCount() + " 个概念, "
                        + genResult.getRelationshipCount() + " 条关系", graph);
    }

    /** 概念详情 */
    @GetMapping("/concepts/{id}")
    public Result<Map<String, Object>> getConceptDetail(@PathVariable Long id) {
        Map<String, Object> detail = kgService.getConceptDetail(id);
        if (detail == null) {
            return Result.fail("概念不存在");
        }
        return Result.success(detail);
    }

    /** 概念在各文档中的来源 */
    @GetMapping("/concepts/{id}/documents")
    public Result<List<Map<String, Object>>> getConceptDocuments(@PathVariable Long id) {
        return Result.success(kgService.getConceptDocuments(id));
    }
}
