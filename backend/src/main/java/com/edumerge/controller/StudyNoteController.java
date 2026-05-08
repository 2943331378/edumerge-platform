package com.edumerge.controller;

import com.edumerge.ai.AiNoteGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.Document;
import com.edumerge.entity.StudyNote;
import com.edumerge.service.DocumentService;
import com.edumerge.service.StudyNoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
        AiNoteGenerator.StudyNoteResult generated = aiNoteGenerator.generate(docId, 1L, doc.getDocumentId());
        if (!generated.isSuccess()) {
            return Result.fail("学习笔记生成失败: 未从文档中提取到足够内容");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", generated.getDeckId());
        data.put("docId", generated.getDocId());
        data.put("title", generated.getTitle());
        data.put("content", generated.getContent());
        data.put("sourceSummary", generated.getSourceSummary());
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
        data.put("createdAt", note.getCreatedAt() != null ? note.getCreatedAt().toString() : null);
        return data;
    }
}
