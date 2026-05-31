package com.edumerge.controller;

import com.edumerge.ai.AiQuizGenerator;
import com.edumerge.common.result.Result;
import com.edumerge.entity.Quiz;
import com.edumerge.entity.QuizAttempt;
import com.edumerge.mapper.QuizAttemptMapper;
import com.edumerge.security.SecurityUtils;
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
    private final QuizAttemptMapper quizAttemptMapper;

    @Autowired
    public QuizController(QuizService quizService, AiQuizGenerator aiQuizGenerator,
                          SessionService sessionService, QuizAttemptMapper quizAttemptMapper) {
        this.quizService = quizService;
        this.aiQuizGenerator = aiQuizGenerator;
        this.sessionService = sessionService;
        this.quizAttemptMapper = quizAttemptMapper;
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

        List<Quiz> quizzes = aiQuizGenerator.generate(Long.parseLong(docIdStr), SecurityUtils.getCurrentUserId(), docUuid);
        return quizzes.isEmpty() ? Result.fail("未检索到文档内容，生成失败") : Result.success("测试题生成成功", quizzes);
    }

    @GetMapping
    public Result<List<Quiz>> list(@RequestParam(required = false) Long docId,
                                    @RequestParam(required = false) Long sessionId,
                                    @RequestParam(required = false) Long deckId) {
        if (sessionId != null) docId = sessionService.resolveDocId(sessionId);
        if (deckId != null) return Result.success(quizService.listByDeckId(deckId));
        return Result.success(docId != null ? quizService.listByDocId(docId) : List.of());
    }

    /** 保存测验答题记录 */
    @PostMapping("/attempts")
    public Result<QuizAttempt> saveAttempt(@RequestBody QuizAttempt attempt) {
        attempt.setUserId(SecurityUtils.getCurrentUserId());
        quizAttemptMapper.insert(attempt);
        log.info("测验记录已保存: id={}, docId={}, score={}%", attempt.getId(), attempt.getDocId(), attempt.getScorePercent());
        return Result.success("答题记录已保存", attempt);
    }

    /** 查询某文档的答题历史 */
    @GetMapping("/attempts")
    public Result<List<QuizAttempt>> listAttempts(@RequestParam Long docId) {
        List<QuizAttempt> attempts = quizAttemptMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getDocId, docId)
                        .orderByDesc(QuizAttempt::getCreatedAt));
        return Result.success(attempts);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Quiz quiz) {
        if (quiz.getQuestion() != null && quiz.getQuestion().isBlank()) return Result.fail("问题不能为空");
        quiz.setId(id);
        quizService.updateById(quiz);
        return Result.success("题目已更新", null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (quizService.deleteById(id) == 0) return Result.fail("题目不存在");
        return Result.success("题目已删除", null);
    }
}
