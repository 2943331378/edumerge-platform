package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.DtoMapper;
import com.edumerge.dto.DocumentResponse;
import com.edumerge.dto.OutlineResponse;
import com.edumerge.entity.Document;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
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
    public Result<?> upload(@RequestParam("file") MultipartFile[] files) throws IOException {
        if (files.length == 0) {
            return Result.fail("文件不能为空");
        }
        if (files.length == 1) {
            MultipartFile file = files[0];
            if (file.isEmpty()) return Result.fail("文件不能为空");
            Map<String, Object> data = documentService.upload(
                    file.getOriginalFilename(), file.getSize(), file.getInputStream());
            return Result.success("上传成功，正在异步处理", data);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            results.add(documentService.upload(
                    file.getOriginalFilename(), file.getSize(), file.getInputStream()));
        }
        return Result.success("上传成功，正在异步处理", results);
    }

    @GetMapping
    public Result<List<DocumentResponse>> list() {
        return Result.success(DtoMapper.toResponseList(documentService.listByUserId(SecurityUtils.getCurrentUserId())));
    }

    @GetMapping("/{docId}/chunks")
    public Result<List<com.edumerge.entity.DocumentChunk>> chunks(@PathVariable Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(documentService.listChunks(docId));
    }

    @PutMapping("/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        documentService.verifyOwnership(id);
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return Result.fail("标题不能为空");
        }
        documentService.rename(id, title.trim());
        return Result.success("已重命名", null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.verifyOwnership(id);
        documentService.delete(id);
        return Result.success("文档已删除", null);
    }

    @PostMapping("/{id}/retry")
    public Result<Void> retry(@PathVariable Long id) {
        documentService.verifyOwnership(id);
        documentService.retryAndSendMessage(id);
        return Result.success("已重新提交处理", null);
    }

    // ═══════ 文档大纲 API ═══════

    @GetMapping("/{docId}/outline")
    public Result<OutlineResponse> getOutline(@PathVariable Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(documentService.getOutline(docId));
    }

    @PutMapping("/{docId}/outline")
    public Result<OutlineResponse> updateOutline(@PathVariable Long docId, @RequestBody String body) {
        documentService.verifyOwnership(docId);
        return Result.success("大纲已保存", documentService.updateOutline(docId, body));
    }

    @PostMapping("/{docId}/outline/regenerate")
    public Result<OutlineResponse> regenerateOutline(@PathVariable Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success("大纲已重新生成", documentService.regenerateOutline(docId));
    }
}
