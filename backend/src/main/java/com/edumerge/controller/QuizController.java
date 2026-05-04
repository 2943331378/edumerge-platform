package com.edumerge.controller;

import com.edumerge.ai.AiQuizGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.Quiz;
import com.edumerge.service.QuizService;
import com.edumerge.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 测试题接口 — 生成委托给 AiQuizGenerator, 查询委托给 QuizService
 */
@Slf4j
@RestController
@RequestMapping("/quizzes")
public class QuizController {

    private final QuizService quizService;
    private final AiQuizGenerator aiQuizGenerator;
    private final SessionService sessionService;

    @Autowired
    public QuizController(QuizService quizService, AiQuizGenerator aiQuizGenerator,
                          SessionService sessionService) {
        this.quizService = quizService;
        this.aiQuizGenerator = aiQuizGenerator;
        this.sessionService = sessionService;
    }

    @PostMapping("/generate")
    public Result<List<Quiz>> generate(@RequestBody Map<String, String> body) {
        String docIdStr = body.get("docId");
        String docUuid = body.get("docUuid");
        String sessionIdStr = body.get("sessionId");

        // sessionId 优先: 解析为 docId + docUuid
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                Long sid = Long.parseLong(sessionIdStr);
                docIdStr = String.valueOf(sessionService.resolveDocId(sid));
                docUuid = sessionService.resolveDocUuid(sid);
            } catch (Exception e) {
                return Result.fail("会话无效: " + e.getMessage());
            }
        }

        if (docIdStr == null || docUuid == null) return Result.fail("docId/sessionId 和 docUuid 不能为空");

        List<Quiz> quizzes = aiQuizGenerator.generate(Long.parseLong(docIdStr), 1L, docUuid);
        return quizzes.isEmpty() ? Result.fail("未检索到文档内容，生成失败") : Result.success("测试题生成成功", quizzes);
    }

    @GetMapping
    public Result<List<Quiz>> list(@RequestParam(required = false) Long docId,
                                    @RequestParam(required = false) Long sessionId) {
        if (sessionId != null) docId = sessionService.resolveDocId(sessionId);
        return Result.success(docId != null ? quizService.listByDocId(docId) : List.of());
    }
}
