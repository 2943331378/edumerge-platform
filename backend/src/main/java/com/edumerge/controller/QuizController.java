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

import java.util.*;

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

        Long docId = Long.parseLong(docIdStr);
        // 加载已有题目，传问题列表给 AI 以避免重复
        List<String> existingQuestions = quizService.listByDocId(docId).stream()
                .map(Quiz::getQuestion).toList();

        List<Quiz> quizzes = aiQuizGenerator.generate(docId, SecurityUtils.getCurrentUserId(), docUuid, existingQuestions);
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

    /** 全局错题本: 聚合所有答题记录中的错误题目 */
    @GetMapping("/error-book")
    public Result<List<Map<String, Object>> > listErrorBook(@RequestParam Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<QuizAttempt> attempts = quizAttemptMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getDocId, docId)
                        .eq(QuizAttempt::getUserId, userId)
                        .orderByDesc(QuizAttempt::getCreatedAt));

        // 统计每道题的错误次数
        com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<Long, int[]> errorMap = new LinkedHashMap<>(); // quizId -> [errorCount]
        for (QuizAttempt a : attempts) {
            if (a.getAnswerDetails() == null) continue;
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> details = jsonMapper.readValue(a.getAnswerDetails(), List.class);
                for (Map<String, Object> d : details) {
                    Object correctObj = d.get("correct");
                    boolean correct = correctObj instanceof Boolean ? (Boolean) correctObj : "true".equals(String.valueOf(correctObj));
                    if (!correct) {
                        Object quizIdObj = d.get("quizId");
                        Long quizId = quizIdObj instanceof Number ? ((Number) quizIdObj).longValue() : Long.parseLong(String.valueOf(quizIdObj));
                        errorMap.computeIfAbsent(quizId, k -> new int[]{0});
                        errorMap.get(quizId)[0]++;
                    }
                }
            } catch (Exception e) {
                log.warn("解析 answer_details 失败: attemptId={}", a.getId(), e);
            }
        }

        // 批量查询所有错题对应的 Quiz 实体（避免 N+1）
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, int[]> entry : errorMap.entrySet()) {
            Quiz quiz = quizService.getById(entry.getKey());
            if (quiz == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("quizId", quiz.getId());
            item.put("question", quiz.getQuestion());
            item.put("options", quiz.getOptions());
            item.put("answer", quiz.getAnswer());
            item.put("explanation", quiz.getExplanation());
            item.put("errorCount", entry.getValue()[0]);
            item.put("deckId", quiz.getDeckId());
            result.add(item);
        }

        // 按错误次数降序
        result.sort((a, b) -> Integer.compare((int) b.get("errorCount"), (int) a.get("errorCount")));
        return Result.success(result);
    }

    /** 按知识点(deck)统计正确率 — 用于薄弱度热力图 */
    @GetMapping("/weakness")
    public Result<List<Map<String, Object>>> listWeakness(@RequestParam Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<QuizAttempt> attempts = quizAttemptMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getDocId, docId)
                        .eq(QuizAttempt::getUserId, userId));

        // 批量收集所有涉及的 quizId，一次性查询避免 N+1
        Set<Long> allQuizIds = new LinkedHashSet<>();
        com.fasterxml.jackson.databind.ObjectMapper jsonMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (QuizAttempt a : attempts) {
            if (a.getAnswerDetails() == null) continue;
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> details = jsonMapper.readValue(a.getAnswerDetails(), List.class);
                for (Map<String, Object> d : details) {
                    Object quizIdObj = d.get("quizId");
                    Long quizId = quizIdObj instanceof Number ? ((Number) quizIdObj).longValue() : Long.parseLong(String.valueOf(quizIdObj));
                    allQuizIds.add(quizId);
                }
            } catch (Exception e) {
                log.warn("解析 answer_details 失败: attemptId={}", a.getId(), e);
            }
        }

        // 一次性批量查询所有涉及的 Quiz，建立 id→deckId 映射
        Map<Long, Long> quizDeckMap = new LinkedHashMap<>();
        for (Long quizId : allQuizIds) {
            Quiz quiz = quizService.getById(quizId);
            if (quiz != null && quiz.getDeckId() != null) {
                quizDeckMap.put(quizId, quiz.getDeckId());
            }
        }

        // 按 deckId 统计: [totalQuestions, correctCount]
        Map<Long, long[]> deckStats = new LinkedHashMap<>();
        for (QuizAttempt a : attempts) {
            if (a.getAnswerDetails() == null) continue;
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> details = jsonMapper.readValue(a.getAnswerDetails(), List.class);
                for (Map<String, Object> d : details) {
                    Object correctObj = d.get("correct");
                    boolean correct = correctObj instanceof Boolean ? (Boolean) correctObj : "true".equals(String.valueOf(correctObj));
                    Object quizIdObj = d.get("quizId");
                    Long quizId = quizIdObj instanceof Number ? ((Number) quizIdObj).longValue() : Long.parseLong(String.valueOf(quizIdObj));
                    Long deckId = quizDeckMap.get(quizId);
                    if (deckId == null) continue;
                    deckStats.computeIfAbsent(deckId, k -> new long[]{0, 0});
                    deckStats.get(deckId)[0]++;
                    if (correct) deckStats.get(deckId)[1]++;
                }
            } catch (Exception e) {
                log.warn("解析 answer_details 失败: attemptId={}", a.getId(), e);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, long[]> entry : deckStats.entrySet()) {
            long total = entry.getValue()[0];
            long correct = entry.getValue()[1];
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deckId", entry.getKey());
            item.put("totalQuestions", total);
            item.put("correctCount", correct);
            item.put("accuracyRate", total > 0 ? Math.round(correct * 100.0 / total) : 0);
            result.add(item);
        }

        result.sort(Comparator.comparingLong(a -> (long) a.get("accuracyRate")));
        return Result.success(result);
    }
}
