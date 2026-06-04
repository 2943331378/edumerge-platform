package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.*;
import com.edumerge.entity.Conversation;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.ConversationService;
import jakarta.validation.Valid;
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
    public Result<List<ConversationResponse>> list(@RequestParam(required = false) Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<Conversation> list = docId != null
                ? conversationService.listByDocId(userId, docId)
                : conversationService.listByUserId(userId);
        return Result.success(DtoMapper.toConversationResponseList(list));
    }

    @PutMapping("/{sessionId}")
    public Result<Void> rename(@PathVariable String sessionId, @Valid @RequestBody RenameConversationRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        conversationService.updateTitle(sessionId, req.getTitle().trim(), userId);
        return Result.success("已重命名", null);
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable String sessionId) {
        Long userId = SecurityUtils.getCurrentUserId();
        conversationService.delete(sessionId, userId);
        return Result.success("对话已删除", null);
    }
}
