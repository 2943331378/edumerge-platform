package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.*;
import com.edumerge.entity.FlowNote;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.DocumentService;
import com.edumerge.service.FlowNoteService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/flownote")
public class FlowNoteController {

    private final FlowNoteService flowNoteService;
    private final DocumentService documentService;

    public FlowNoteController(FlowNoteService flowNoteService, DocumentService documentService) {
        this.flowNoteService = flowNoteService;
        this.documentService = documentService;
    }

    /** 查询 FlowNote 条目列表 */
    @GetMapping
    public Result<List<FlowNoteResponse>> list(@RequestParam Long docId,
                                                @RequestParam(required = false) String category) {
        Long userId = SecurityUtils.getCurrentUserId();
        return Result.success(DtoMapper.toFlowNoteResponseList(flowNoteService.listByDocIdAndCategory(docId, userId, category)));
    }

    /** AI 从对话中提取 FlowNote 条目 */
    @PostMapping("/extract")
    public Result<List<FlowNoteResponse>> extract(@RequestBody FlowNoteExtractRequest req) {
        if (req.getDocId() == null) {
            return Result.fail("docId 不能为空");
        }

        var doc = documentService.getById(req.getDocId());
        if (doc == null) {
            return Result.fail("文档不存在");
        }

        int maxExchanges = req.getMaxExchanges() != null ? req.getMaxExchanges() : 10;
        Long userId = SecurityUtils.getCurrentUserId();

        List<FlowNote> notes = flowNoteService.extractFromChat(
                req.getDocId(), doc.getDocumentId(), userId,
                req.getSessionId(), maxExchanges);
        return Result.success("提取完成，共生成 " + notes.size() + " 条笔记", DtoMapper.toFlowNoteResponseList(notes));
    }

    /** 手动创建条目 */
    @PostMapping("/entries")
    public Result<FlowNoteResponse> create(@Valid @RequestBody FlowNoteCreateRequest req) {
        FlowNote note = FlowNote.builder()
                .userId(SecurityUtils.getCurrentUserId())
                .docId(req.getDocId())
                .sessionId(req.getSessionId())
                .category(req.getCategory())
                .title(req.getTitle())
                .content(req.getContent())
                .sourceType("USER_WRITTEN")
                .build();
        flowNoteService.create(note);
        return Result.success("条目已创建", DtoMapper.toResponse(note));
    }

    /** 更新条目 */
    @PutMapping("/entries/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody FlowNoteUpdateRequest req) {
        FlowNote note = new FlowNote();
        note.setCategory(req.getCategory());
        note.setTitle(req.getTitle());
        note.setContent(req.getContent());
        note.setSourceSegment(req.getSourceSegment());
        flowNoteService.update(id, note);
        return Result.success("条目已更新", null);
    }

    /** 删除条目 */
    @DeleteMapping("/entries/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        flowNoteService.delete(id);
        return Result.success("条目已删除", null);
    }

    /** 标记已复习 */
    @PutMapping("/entries/{id}/review")
    public Result<Void> markReviewed(@PathVariable Long id) {
        flowNoteService.markReviewed(id);
        return Result.success("已标记为复习", null);
    }

    /** 统计 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return Result.success(flowNoteService.stats(docId, userId));
    }
}
