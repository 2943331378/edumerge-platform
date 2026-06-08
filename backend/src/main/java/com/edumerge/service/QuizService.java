package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.ai.AiQuizGenerator;
import com.edumerge.entity.Document;
import com.edumerge.entity.Quiz;
import com.edumerge.entity.QuizAttempt;
import com.edumerge.mapper.QuizAttemptMapper;
import com.edumerge.mapper.QuizMapper;
import com.edumerge.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 测试题业务服务 — CRUD + AI 生成 + 答题统计
 */
@Slf4j
@Service
public class QuizService {

    private final QuizMapper quizMapper;
    private final QuizAttemptMapper quizAttemptMapper;
    private final AiQuizGenerator aiQuizGenerator;
    private final SessionService sessionService;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper;

    @Autowired
    public QuizService(QuizMapper quizMapper,
                       QuizAttemptMapper quizAttemptMapper,
                       AiQuizGenerator aiQuizGenerator,
                       SessionService sessionService,
                       DocumentService documentService,
                       ObjectMapper objectMapper) {
        this.quizMapper = quizMapper;
        this.quizAttemptMapper = quizAttemptMapper;
        this.aiQuizGenerator = aiQuizGenerator;
        this.sessionService = sessionService;
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    // ═══════ CRUD ═══════

    @Transactional(readOnly = true)
    public List<Quiz> listByDocId(Long docId) {
        return quizMapper.selectList(
                new LambdaQueryWrapper<Quiz>()
                        .eq(Quiz::getDocId, docId)
                        .orderByAsc(Quiz::getId));
    }

    @Transactional(readOnly = true)
    public List<Quiz> listByDocIdAndType(Long docId, String quizType) {
        return quizMapper.selectList(
                new LambdaQueryWrapper<Quiz>()
                        .eq(Quiz::getDocId, docId)
                        .eq(Quiz::getQuizType, quizType)
                        .orderByAsc(Quiz::getId));
    }

    @Transactional
    public Quiz create(Quiz quiz) {
        quizMapper.insert(quiz);
        log.info("测试题已创建: id={}, docId={}", quiz.getId(), quiz.getDocId());
        return quiz;
    }

    @Transactional
    public void batchCreate(List<Quiz> quizzes) {
        quizMapper.insert(quizzes, 50);
        log.info("批量创建测试题完成: 数量={}", quizzes.size());
    }

    @Transactional(readOnly = true)
    public List<Quiz> listByDeckId(Long deckId) {
        return quizMapper.selectList(
                new LambdaQueryWrapper<Quiz>()
                        .eq(Quiz::getDeckId, deckId)
                        .orderByAsc(Quiz::getId));
    }

    @Transactional
    public void updateById(Quiz quiz) {
        verifyOwnership(quiz.getId());
        quizMapper.updateById(quiz);
        log.info("测试题已更新: id={}", quiz.getId());
    }

    @Transactional
    public int deleteById(Long id) {
        verifyOwnership(id);
        int rows = quizMapper.deleteById(id);
        if (rows > 0) log.info("测试题已删除: id={}", id);
        return rows;
    }

    private void verifyOwnership(Long quizId) {
        Quiz quiz = getById(quizId);
        if (quiz == null) throw new IllegalArgumentException("题目不存在: " + quizId);
        Long userId = SecurityUtils.getCurrentUserId();
        Document doc = documentService.getById(quiz.getDocId());
        if (doc == null || !doc.getUserId().equals(userId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权操作此题目");
        }
    }

    @Transactional(readOnly = true)
    public Quiz getById(Long id) {
        return quizMapper.selectById(id);
    }

    /** sessionId → docId 委托 */
    public Long resolveDocId(Long sessionId) {
        return sessionService.resolveDocId(sessionId);
    }

    // ═══════ AI 生成 ═══════

    /**
     * 生成测试题（支持 sessionId 或 docId+docUuid 两种入参）
     *
     * @return 生成的测试题列表
     * @throws IllegalArgumentException 参数校验失败
     */
    @Transactional
    public List<Quiz> generate(String docIdStr, String docUuid, String sessionIdStr, String sectionContext, Integer startChunk, Integer endChunk) {
        // sessionId 优先: 解析为 docId + docUuid
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                Long sid = Long.parseLong(sessionIdStr);
                docIdStr = String.valueOf(sessionService.resolveDocId(sid));
                docUuid = sessionService.resolveDocUuid(sid);
            } catch (Exception e) {
                throw new IllegalArgumentException("会话无效: " + e.getMessage());
            }
        }

        if (docIdStr == null || docUuid == null) {
            throw new IllegalArgumentException("docId/sessionId 和 docUuid 不能为空");
        }

        Long docId = Long.parseLong(docIdStr);
        Long userId = SecurityUtils.getCurrentUserId();

        // 加载已有题目，传问题列表给 AI 以避免重复
        List<String> existingQuestions = listByDocId(docId).stream()
                .map(Quiz::getQuestion).toList();

        List<Quiz> quizzes = aiQuizGenerator.generate(docId, userId, docUuid, existingQuestions, sectionContext, startChunk, endChunk);
        if (quizzes.isEmpty()) {
            throw new IllegalStateException("未检索到文档内容，生成失败");
        }
        return quizzes;
    }

    // ═══════ 答题记录 ═══════

    /** 保存答题记录 */
    @Transactional
    public QuizAttempt saveAttempt(QuizAttempt attempt) {
        attempt.setUserId(SecurityUtils.getCurrentUserId());
        quizAttemptMapper.insert(attempt);
        log.info("测验记录已保存: id={}, docId={}, score={}%", attempt.getId(), attempt.getDocId(), attempt.getScorePercent());
        return attempt;
    }

    /** 查询某文档的答题历史（仅当前用户） */
    @Transactional(readOnly = true)
    public List<QuizAttempt> listAttempts(Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getDocId, docId)
                        .eq(QuizAttempt::getUserId, userId)
                        .orderByDesc(QuizAttempt::getCreatedAt));
    }

    // ═══════ 错题本 & 薄弱度 ═══════

    /**
     * 全局错题本: 聚合所有答题记录中的错误题目
     *
     * @return 每项含 quizId, question, options, answer, explanation, errorCount, deckId
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getErrorBook(Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<QuizAttempt> attempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getDocId, docId)
                        .eq(QuizAttempt::getUserId, userId)
                        .orderByDesc(QuizAttempt::getCreatedAt));

        // 统计每道题的错误次数
        Map<Long, int[]> errorMap = new LinkedHashMap<>();
        for (QuizAttempt a : attempts) {
            if (a.getAnswerDetails() == null) continue;
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> details = objectMapper.readValue(a.getAnswerDetails(), List.class);
                for (Map<String, Object> d : details) {
                    boolean correct = parseCorrect(d.get("correct"));
                    if (!correct) {
                        Long quizId = parseQuizId(d.get("quizId"));
                        errorMap.computeIfAbsent(quizId, k -> new int[]{0});
                        errorMap.get(quizId)[0]++;
                    }
                }
            } catch (Exception e) {
                log.warn("解析 answer_details 失败: attemptId={}", a.getId(), e);
            }
        }

        // 查询错题对应的 Quiz 实体
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, int[]> entry : errorMap.entrySet()) {
            Quiz quiz = getById(entry.getKey());
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

        result.sort((a, b) -> Integer.compare((int) b.get("errorCount"), (int) a.get("errorCount")));
        return result;
    }

    /**
     * 按知识点(deck)统计正确率 — 用于薄弱度热力图
     *
     * @return 每项含 deckId, totalQuestions, correctCount, accuracyRate
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getWeakness(Long docId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<QuizAttempt> attempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getDocId, docId)
                        .eq(QuizAttempt::getUserId, userId));

        // 批量收集所有涉及的 quizId，建立 id→deckId 映射
        Set<Long> allQuizIds = new LinkedHashSet<>();
        for (QuizAttempt a : attempts) {
            if (a.getAnswerDetails() == null) continue;
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> details = objectMapper.readValue(a.getAnswerDetails(), List.class);
                for (Map<String, Object> d : details) {
                    allQuizIds.add(parseQuizId(d.get("quizId")));
                }
            } catch (Exception e) {
                log.warn("解析 answer_details 失败: attemptId={}", a.getId(), e);
            }
        }

        Map<Long, Long> quizDeckMap = new LinkedHashMap<>();
        for (Long quizId : allQuizIds) {
            Quiz quiz = getById(quizId);
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
                List<Map<String, Object>> details = objectMapper.readValue(a.getAnswerDetails(), List.class);
                for (Map<String, Object> d : details) {
                    boolean correct = parseCorrect(d.get("correct"));
                    Long quizId = parseQuizId(d.get("quizId"));
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
        return result;
    }

    // ═══════ 内部工具 ═══════

    private boolean parseCorrect(Object correctObj) {
        if (correctObj instanceof Boolean b) return b;
        return "true".equals(String.valueOf(correctObj));
    }

    private Long parseQuizId(Object quizIdObj) {
        if (quizIdObj instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(quizIdObj));
    }
}
