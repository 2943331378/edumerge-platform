package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.entity.Conversation;
import com.edumerge.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public Result<List<Conversation>> list() {
        return Result.success(conversationService.listByUserId(1L));
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable String sessionId) {
        conversationService.delete(sessionId);
        return Result.success("对话已删除", null);
    }
}
