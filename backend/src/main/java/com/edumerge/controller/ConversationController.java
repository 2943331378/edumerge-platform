package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.entity.Conversation;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    @Autowired
    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public Result<List<Conversation>> list(@RequestParam(required = false) Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (docId != null) {
            return Result.success(conversationService.listByDocId(userId, docId));
        }
        return Result.success(conversationService.listByUserId(userId));
    }

    @PutMapping("/{sessionId}")
    public Result<Void> rename(@PathVariable String sessionId, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) return Result.fail("标题不能为空");
        Long userId = SecurityUtils.getCurrentUserId();
        conversationService.updateTitle(sessionId, title.trim(), userId);
        return Result.success("已重命名", null);
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable String sessionId) {
        Long userId = SecurityUtils.getCurrentUserId();
        conversationService.delete(sessionId, userId);
        return Result.success("对话已删除", null);
    }
}
