package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.*;
import com.edumerge.entity.Quiz;
import com.edumerge.entity.QuizAttempt;
import com.edumerge.service.DocumentService;
import com.edumerge.service.QuizService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 测试题接口 — 业务逻辑委托给 QuizService
 */
@Slf4j
@RestController
@RequestMapping("/quizzes")
public class QuizController {

    private final QuizService quizService;
    private final DocumentService documentService;

    @Autowired
    public QuizController(QuizService quizService, DocumentService documentService) {
        this.quizService = quizService;
        this.documentService = documentService;
    }

    @PostMapping("/generate")
    public Result<List<QuizResponse>> generate(@RequestBody Map<String, String> body) {
        List<Quiz> quizzes = quizService.generate(
                body.get("docId"), body.get("docUuid"), body.get("sessionId"), body.get("sectionContext"));
        return Result.success("测试题生成成功", DtoMapper.toQuizResponseList(quizzes));
    }

    @GetMapping
    public Result<List<QuizResponse>> list(@RequestParam(required = false) Long docId,
                                            @RequestParam(required = false) Long sessionId,
                                            @RequestParam(required = false) Long deckId) {
        List<Quiz> quizzes;
        if (deckId != null) quizzes = quizService.listByDeckId(deckId);
        else {
            if (sessionId != null) docId = quizService.resolveDocId(sessionId);
            if (docId != null) documentService.verifyOwnership(docId);
            quizzes = docId != null ? quizService.listByDocId(docId) : List.of();
        }
        return Result.success(DtoMapper.toQuizResponseList(quizzes));
    }

    @PostMapping("/attempts")
    public Result<QuizAttemptResponse> saveAttempt(@RequestBody QuizAttempt attempt) {
        return Result.success("答题记录已保存", DtoMapper.toResponse(quizService.saveAttempt(attempt)));
    }

    @GetMapping("/attempts")
    public Result<List<QuizAttemptResponse>> listAttempts(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(DtoMapper.toQuizAttemptResponseList(quizService.listAttempts(docId)));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateQuizRequest req) {
        Quiz quiz = new Quiz();
        quiz.setId(id);
        quiz.setQuestion(req.getQuestion());
        quiz.setOptions(req.getOptions());
        quiz.setAnswer(req.getAnswer());
        quiz.setExplanation(req.getExplanation());
        quiz.setStatus(req.getStatus());
        quiz.setDifficulty(req.getDifficulty());
        quizService.updateById(quiz);
        return Result.success("题目已更新", null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (quizService.deleteById(id) == 0) return Result.fail("题目不存在");
        return Result.success("题目已删除", null);
    }

    @GetMapping("/error-book")
    public Result<List<Map<String, Object>>> listErrorBook(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(quizService.getErrorBook(docId));
    }

    @GetMapping("/weakness")
    public Result<List<Map<String, Object>>> listWeakness(@RequestParam Long docId) {
        documentService.verifyOwnership(docId);
        return Result.success(quizService.getWeakness(docId));
    }
}
