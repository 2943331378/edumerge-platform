package com.edumerge.controller;

import com.edumerge.ai.AiNoteGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.Document;
import com.edumerge.entity.StudyNote;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.DocumentService;
import com.edumerge.service.StudyNoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/notes")
public class StudyNoteController {

    private final StudyNoteService studyNoteService;
    private final AiNoteGenerator aiNoteGenerator;
    private final DocumentService documentService;

    @Autowired
    public StudyNoteController(StudyNoteService studyNoteService,
                               AiNoteGenerator aiNoteGenerator,
                               DocumentService documentService) {
        this.studyNoteService = studyNoteService;
        this.aiNoteGenerator = aiNoteGenerator;
        this.documentService = documentService;
    }

    @GetMapping
    public Result<Map<String, Object>> getNote(@RequestParam Long docId) {
        StudyNote note = studyNoteService.getByDocId(docId);
        if (note == null) {
            return Result.fail("该文档暂无学习笔记");
        }
        return Result.success(toMap(note));
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> getHistory(@RequestParam Long docId) {
        List<StudyNote> notes = studyNoteService.listByDocId(docId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (StudyNote note : notes) {
            list.add(toMap(note));
        }
        return Result.success(list);
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String content = body.get("content");
        String title = body.get("title");
        if (content == null && title == null) {
            return Result.fail("content 和 title 不能同时为空");
        }
        try {
            StudyNote updated = studyNoteService.update(id, content, title);
            return Result.success(toMap(updated));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PostMapping("/generate")
    public Result<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        String docIdStr = body.get("docId");
        if (docIdStr == null || docIdStr.isBlank()) {
            return Result.fail("docId 不能为空");
        }

        Long docId = Long.parseLong(docIdStr);
        Document doc = documentService.getById(docId);
        if (doc == null) {
            return Result.fail("文档不存在: " + docId);
        }
        if (!"COMPLETED".equalsIgnoreCase(doc.getStatus())) {
            return Result.fail("文档尚未完成向量化，当前状态: " + doc.getStatus());
        }
        if (doc.getDocumentId() == null || doc.getDocumentId().isBlank()) {
            return Result.fail("文档缺少向量检索标识，无法生成学习笔记");
        }

        log.info("开始生成学习笔记: docId={}, docUuid={}", docId, doc.getDocumentId());
        String requirements = body.get("requirements");
        AiNoteGenerator.StudyNoteResult generated = aiNoteGenerator.generate(docId, SecurityUtils.getCurrentUserId(), doc.getDocumentId(), requirements);
        if (!generated.isSuccess()) {
            return Result.fail("学习笔记生成失败: 未从文档中提取到足够内容");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", generated.getDeckId());
        data.put("docId", generated.getDocId());
        data.put("title", generated.getTitle());
        data.put("content", generated.getContent());
        data.put("sourceSummary", generated.getSourceSummary());
        data.put("requirements", generated.getRequirements());
        data.put("createdAt", java.time.LocalDateTime.now().toString());
        return Result.success(data);
    }

    private Map<String, Object> toMap(StudyNote note) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", note.getId());
        data.put("deckId", note.getDeckId());
        data.put("docId", note.getDocId());
        data.put("title", note.getTitle());
        data.put("content", note.getContent());
        data.put("sourceSummary", note.getSourceSummary());
        data.put("requirements", note.getRequirements());
        data.put("createdAt", note.getCreatedAt() != null ? note.getCreatedAt().toString() : null);
        return data;
    }
}
