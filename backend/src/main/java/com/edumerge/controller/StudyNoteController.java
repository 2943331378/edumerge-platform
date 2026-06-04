package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.entity.StudyNote;
import com.edumerge.service.StudyNoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学习笔记接口 — 业务逻辑委托给 StudyNoteService
 */
@Slf4j
@RestController
@RequestMapping("/notes")
public class StudyNoteController {

    private final StudyNoteService studyNoteService;

    @Autowired
    public StudyNoteController(StudyNoteService studyNoteService) {
        this.studyNoteService = studyNoteService;
    }

    @GetMapping
    public Result<Map<String, Object>> getNote(@RequestParam Long docId) {
        StudyNote note = studyNoteService.getByDocId(docId);
        if (note == null) {
            return Result.fail("该文档暂无学习笔记");
        }
        return Result.success(studyNoteService.toMap(note));
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> getHistory(@RequestParam Long docId) {
        List<StudyNote> notes = studyNoteService.listByDocId(docId);
        return Result.success(notes.stream().map(studyNoteService::toMap).toList());
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String content = body.get("content");
        String title = body.get("title");
        if (content == null && title == null) {
            return Result.fail("content 和 title 不能同时为空");
        }
        StudyNote updated = studyNoteService.update(id, content, title);
        return Result.success(studyNoteService.toMap(updated));
    }

    @PostMapping("/generate")
    public Result<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        String docIdStr = body.get("docId");
        if (docIdStr == null || docIdStr.isBlank()) {
            return Result.fail("docId 不能为空");
        }
        Long docId = Long.parseLong(docIdStr);
        Map<String, Object> data = studyNoteService.generate(docId, body.get("requirements"), body.get("sectionContext"));
        return Result.success(data);
    }
}
