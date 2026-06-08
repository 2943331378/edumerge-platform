package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.service.DocumentService;
import com.edumerge.service.MindMapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 思维导图接口 — 业务逻辑委托给 MindMapService
 */
@Slf4j
@RestController
@RequestMapping("/mindmap")
public class MindMapController {

    private final MindMapService mindMapService;
    private final DocumentService documentService;

    @Autowired
    public MindMapController(MindMapService mindMapService, DocumentService documentService) {
        this.mindMapService = mindMapService;
        this.documentService = documentService;
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> listMindMaps(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(mindMapService.listMindMaps(docId));
    }

    @GetMapping("/detail")
    public Result<Map<String, Object>> getMindMapDetail(@RequestParam Long deckId) {
        mindMapService.verifyOwnershipByDeckId(deckId);
        return Result.success(mindMapService.getMindMapDetail(deckId));
    }

    @PostMapping("/generate")
    public Result<Map<String, Object>> generateMindMap(@RequestParam Long docId,
                                                       @RequestParam(required = false) String sectionContext,
                                                       @RequestParam(required = false) Integer startChunk,
                                                       @RequestParam(required = false) Integer endChunk) {
        documentService.verifyOwnership(docId);
        return Result.success(mindMapService.generate(docId, sectionContext, startChunk, endChunk));
    }

    @DeleteMapping("/{deckId}")
    public Result<Void> deleteMindMap(@PathVariable Long deckId) {
        mindMapService.deleteMindMap(deckId);
        return Result.success(null);
    }

    /** 兼容旧接口: GET /mindmap?docId= → 返回最新一条或自动生成 */
    @GetMapping
    public Result<Map<String, Object>> getMindMap(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(mindMapService.getOrGenerate(docId));
    }
}
