package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.DtoMapper;
import com.edumerge.dto.DocumentResponse;
import com.edumerge.dto.OutlineResponse;
import com.edumerge.entity.Document;
import com.edumerge.service.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 文档上传与查询接口 — 业务逻辑委托给 DocumentService
 */
@Slf4j
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;

    @Autowired
    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return Result.fail("文件不能为空");
        }
        Map<String, Object> data = documentService.upload(
                file.getOriginalFilename(), file.getSize(), file.getInputStream());
        return Result.success("上传成功，正在异步处理", data);
    }

    @GetMapping
    public Result<List<DocumentResponse>> list() {
        return Result.success(DtoMapper.toResponseList(documentService.listRecent()));
    }

    @GetMapping("/{docId}/chunks")
    public Result<List<com.edumerge.entity.DocumentChunk>> chunks(@PathVariable Long docId) {
        Document doc = documentService.getById(docId);
        if (doc == null) {
            return Result.fail("文档不存在");
        }
        return Result.success(documentService.listChunks(docId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.success("文档已删除", null);
    }

    // ═══════ 文档大纲 API ═══════

    @GetMapping("/{docId}/outline")
    public Result<OutlineResponse> getOutline(@PathVariable Long docId) {
        return Result.success(documentService.getOutline(docId));
    }

    @PutMapping("/{docId}/outline")
    public Result<OutlineResponse> updateOutline(@PathVariable Long docId, @RequestBody String body) {
        return Result.success("大纲已保存", documentService.updateOutline(docId, body));
    }

    @PostMapping("/{docId}/outline/regenerate")
    public Result<OutlineResponse> regenerateOutline(@PathVariable Long docId) {
        return Result.success("大纲已重新生成", documentService.regenerateOutline(docId));
    }
}
