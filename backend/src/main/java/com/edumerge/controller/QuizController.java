package com.edumerge.controller;

import com.edumerge.ai.AiQuizGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.Quiz;
import com.edumerge.service.QuizService;
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

    @Autowired
    public QuizController(QuizService quizService, AiQuizGenerator aiQuizGenerator) {
        this.quizService = quizService;
        this.aiQuizGenerator = aiQuizGenerator;
    }

    @PostMapping("/generate")
    public Result<List<Quiz>> generate(@RequestBody Map<String, String> body) {
        String docIdStr = body.get("docId");
        String docUuid = body.get("docUuid");
        if (docIdStr == null || docUuid == null) return Result.fail("docId 和 docUuid 不能为空");

        List<Quiz> quizzes = aiQuizGenerator.generate(Long.parseLong(docIdStr), 1L, docUuid);
        return quizzes.isEmpty() ? Result.fail("未检索到文档内容，生成失败") : Result.success("测试题生成成功", quizzes);
    }

    @GetMapping
    public Result<List<Quiz>> list(@RequestParam(required = false) Long docId) {
        return Result.success(docId != null ? quizService.listByDocId(docId) : List.of());
    }
}
