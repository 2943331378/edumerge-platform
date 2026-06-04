package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.DtoMapper;
import com.edumerge.dto.SessionResponse;
import com.edumerge.entity.Session;
import com.edumerge.security.SecurityUtils;
import com.edumerge.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话接口 — 业务逻辑委托给 SessionService
 */
@Slf4j
@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final SessionService sessionService;

    @Autowired
    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.success(sessionService.listWithDocInfo(SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{id}")
    public Result<SessionResponse> get(@PathVariable Long id) {
        return Result.success(DtoMapper.toResponse(sessionService.getById(id)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sessionService.deleteById(id);
        return Result.success("会话已删除", null);
    }
}
