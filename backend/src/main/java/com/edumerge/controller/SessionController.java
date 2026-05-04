package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.entity.Document;
import com.edumerge.entity.Session;
import com.edumerge.mapper.SessionMapper;
import com.edumerge.service.DocumentService;
import com.edumerge.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionMapper sessionMapper;
    private final DocumentService documentService;

    @Autowired
    public SessionController(SessionService sessionService, SessionMapper sessionMapper,
                             DocumentService documentService) {
        this.sessionService = sessionService;
        this.sessionMapper = sessionMapper;
        this.documentService = documentService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        List<Session> sessions = sessionService.listByUserId(1L);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Session s : sessions) {
            Document doc = documentService.getById(s.getDocId());
            Map<String, Object> item = new HashMap<>();
            item.put("id", s.getId());
            item.put("docId", s.getDocId());
            item.put("docUuid", doc != null ? doc.getDocumentId() : null);
            item.put("title", s.getTitle());
            item.put("status", s.getStatus());
            item.put("fileName", doc != null ? doc.getFileName() : null);
            item.put("docStatus", doc != null ? doc.getStatus() : null);
            item.put("chunkCount", doc != null ? doc.getChunkCount() : null);
            item.put("vectorCount", doc != null ? doc.getVectorCount() : null);
            item.put("createdAt", s.getCreatedAt());
            result.add(item);
        }
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<Session> get(@PathVariable Long id) {
        return Result.success(sessionService.getById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sessionMapper.deleteById(id);
        log.info("会话已删除: id={}", id);
        return Result.success("会话已删除", null);
    }
}
