package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.dto.LearnerDashboardResponse;
import com.edumerge.entity.*;
import com.edumerge.mapper.*;
import com.edumerge.security.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学习者个人中心看板 — 从 StatsService 拆分，专注 Dashboard 数据聚合
 *
 * 优化: 用 SQL 聚合 (COUNT/GROUP BY/SUM) 替代 Java 循环，消除 N+1 查询模式
 */
@Slf4j
@Service
public class LearnerDashboardService {

    private final DocumentMapper documentMapper;
    private final CardDeckMapper cardDeckMapper;
    private final FlashcardMapper flashcardMapper;
    private final QuizMapper quizMapper;
    private final FlashcardReviewLogMapper flashcardReviewLogMapper;
    private final QuizAttemptMapper quizAttemptMapper;
    private final ObjectMapper objectMapper;

    public LearnerDashboardService(DocumentMapper documentMapper,
                                    CardDeckMapper cardDeckMapper,
                                    FlashcardMapper flashcardMapper,
                                    QuizMapper quizMapper,
                                    FlashcardReviewLogMapper flashcardReviewLogMapper,
                                    QuizAttemptMapper quizAttemptMapper,
                                    ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.cardDeckMapper = cardDeckMapper;
        this.flashcardMapper = flashcardMapper;
        this.quizMapper = quizMapper;
        this.flashcardReviewLogMapper = flashcardReviewLogMapper;
        this.quizAttemptMapper = quizAttemptMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "dashboard", key = "T(com.edumerge.security.SecurityUtils).getCurrentUserId()")
    public LearnerDashboardResponse calculate() {
        Long userId = SecurityUtils.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime weekAgo = todayStart.minusDays(6);
        LocalDateTime monthAgo = todayStart.minusDays(29);
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // ===== 今日待办 (SQL COUNT/SUM 替代加载全部记录) =====
        int todayReviewCount = sumCountFromDayRows(
                flashcardReviewLogMapper.countByDay(userId, todayStart), null);
        List<Map<String, Object>> todayQuizAgg = quizAttemptMapper.sumByDay(userId, todayStart);
        long todayTotalQ = 0, todayCorrect = 0;
        int todayQuizCount = 0;
        for (Map<String, Object> row : todayQuizAgg) {
            todayQuizCount += toInt(row, "cnt");
            todayTotalQ += toLong(row, "total");
            todayCorrect += toLong(row, "correct");
        }

        LearnerDashboardResponse.TodayTasks today = LearnerDashboardResponse.TodayTasks.builder()
                .reviewedCards(todayReviewCount)
                .quizAttempts(todayQuizCount)
                .quizAccuracy(todayTotalQ > 0 ? Math.round(todayCorrect * 1000.0 / todayTotalQ) / 10.0 : 0)
                .correctCount(todayCorrect)
                .totalAnswered(todayTotalQ)
                .build();

        // ===== 到期闪卡 (SQL GROUP BY doc_id 替代加载全部卡片) =====
        List<Map<String, Object>> dueRows = flashcardMapper.countDueByDoc(userId, now);
        long totalDueCards = dueRows.stream().mapToLong(r -> toLong(r, "cnt")).sum();
        List<LearnerDashboardResponse.DocDueInfo> dueDocs = buildDueDocs(dueRows);

        // ===== 月度+周度趋势 (SQL GROUP BY day，一次查询替代两次全量加载) =====
        List<Map<String, Object>> monthReviewByDay = flashcardReviewLogMapper.countByDay(userId, monthAgo);
        List<Map<String, Object>> monthQuizByDay = quizAttemptMapper.countByDay(userId, monthAgo);
        List<Map<String, Object>> weekReviewByDay = flashcardReviewLogMapper.countByDay(userId, weekAgo);
        List<Map<String, Object>> weekQuizByDay = quizAttemptMapper.countByDay(userId, weekAgo);

        LearnerDashboardResponse.LearningRhythm rhythm = buildRhythm(
                monthReviewByDay, monthQuizByDay, weekReviewByDay, weekQuizByDay, dayFmt);

        // ===== 累计成就 (SQL COUNT/SUM 替代加载全部记录) =====
        long totalReviews = flashcardReviewLogMapper.selectCount(
                new LambdaQueryWrapper<FlashcardReviewLog>()
                        .eq(FlashcardReviewLog::getUserId, userId));
        Map<String, Object> allQuizAgg = quizAttemptMapper.sumTotalAndCorrect(userId);
        long allTotalQ = toLong(allQuizAgg, "total");
        long allCorrect = toLong(allQuizAgg, "correct");
        long totalQuizAttempts = 0;
        if (allTotalQ > 0) {
            totalQuizAttempts = quizAttemptMapper.selectCount(
                    new LambdaQueryWrapper<QuizAttempt>().eq(QuizAttempt::getUserId, userId));
        }

        long totalDocs = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId).eq(Document::getDeleted, 0));
        long totalFlashcards = flashcardMapper.selectCount(
                new LambdaQueryWrapper<Flashcard>()
                        .eq(Flashcard::getUserId, userId).eq(Flashcard::getDeleted, 0));
        long totalQuizQuestions = quizMapper.countByUserId(userId);

        LearnerDashboardResponse.Achievement achievement = LearnerDashboardResponse.Achievement.builder()
                .totalReviews(totalReviews)
                .totalQuizzes(totalQuizAttempts)
                .avgAccuracy(allTotalQ > 0 ? Math.round(allCorrect * 1000.0 / allTotalQ) / 10.0 : 0)
                .totalDocs(totalDocs)
                .totalFlashcards(totalFlashcards)
                .totalQuizQuestions(totalQuizQuestions)
                .build();

        // ===== 薄弱知识点 (只查 answer_details 列，单次遍历提取错误+文档统计) =====
        Map<Long, String> docNameMap = new HashMap<>();
        Map<Long, Long> errorCountMap = new LinkedHashMap<>();
        Map<Long, long[]> docQuizMap = new LinkedHashMap<>();
        parseAnswerDetails(userId, errorCountMap, docQuizMap);

        List<LearnerDashboardResponse.ErrorItem> topErrors = buildTopErrors(errorCountMap, docNameMap);
        List<LearnerDashboardResponse.DeckWeakness> deckWeaknesses = buildDeckWeaknesses(docQuizMap, docNameMap);

        // ===== 文档学习进度 (SQL GROUP BY doc_id 替代加载全部闪卡) =====
        List<LearnerDashboardResponse.DocProgress> docProgress = buildDocProgress(
                userId, deckWeaknesses, docNameMap);

        // ===== 今日时间线 (只查今日数据，最小列集) =====
        List<LearnerDashboardResponse.TimelineEntry> timeline = buildTimeline(
                userId, todayStart, docNameMap);

        // ===== 周度报告 =====
        // 周度答题正确率 (从已有的月度 sumByDay 数据中筛选本周)
        long weekTotalQ = 0, weekCorrect = 0;
        List<Map<String, Object>> weekQuizSum = quizAttemptMapper.sumByDay(userId, weekAgo);
        for (Map<String, Object> row : weekQuizSum) {
            weekTotalQ += toLong(row, "total");
            weekCorrect += toLong(row, "correct");
        }
        LearnerDashboardResponse.WeeklySummary weeklySummary = buildWeeklySummary(
                userId, todayStart, weekReviewByDay, weekQuizByDay,
                weekTotalQ, weekCorrect, docNameMap);

        log.info("[学习者看板] userId={}, 待复习={}, 连续{}天, 累计复习={}, 正确率={}%",
                userId, totalDueCards, rhythm.getStreakDays(), totalReviews, achievement.getAvgAccuracy());

        return LearnerDashboardResponse.builder()
                .today(today)
                .rhythm(rhythm)
                .achievement(achievement)
                .dueDocs(dueDocs)
                .topErrors(topErrors)
                .deckWeaknesses(deckWeaknesses)
                .docProgress(docProgress)
                .todayTimeline(timeline)
                .weeklySummary(weeklySummary)
                .build();
    }

    // ═══════ 构建各板块 ═══════

    private List<LearnerDashboardResponse.DocDueInfo> buildDueDocs(List<Map<String, Object>> dueRows) {
        if (dueRows.isEmpty()) return List.of();

        Set<Long> docIds = new HashSet<>();
        for (Map<String, Object> row : dueRows) docIds.add(toLong(row, "docId"));
        Map<Long, String> titleMap = fetchDocTitles(docIds);

        List<LearnerDashboardResponse.DocDueInfo> result = new ArrayList<>();
        for (Map<String, Object> row : dueRows) {
            long docId = toLong(row, "docId");
            result.add(LearnerDashboardResponse.DocDueInfo.builder()
                    .docId(docId)
                    .docName(titleMap.getOrDefault(docId, "未知文档"))
                    .dueCount(toLong(row, "cnt"))
                    .build());
        }
        result.sort((a, b) -> Long.compare(b.getDueCount(), a.getDueCount()));
        return result;
    }

    private LearnerDashboardResponse.LearningRhythm buildRhythm(
            List<Map<String, Object>> monthReviewByDay,
            List<Map<String, Object>> monthQuizByDay,
            List<Map<String, Object>> weekReviewByDay,
            List<Map<String, Object>> weekQuizByDay,
            DateTimeFormatter dayFmt) {

        Map<String, Long> wReviewMap = toDayMap(weekReviewByDay);
        Map<String, Long> wQuizMap = toDayMap(weekQuizByDay);
        Map<String, Long> mReviewMap = toDayMap(monthReviewByDay);
        Map<String, Long> mQuizMap = toDayMap(monthQuizByDay);

        List<LearnerDashboardResponse.DailyActivity> weekly = buildDailyActivities(6, wReviewMap, wQuizMap, dayFmt);
        List<LearnerDashboardResponse.DailyActivity> monthly = buildDailyActivities(29, mReviewMap, mQuizMap, dayFmt);

        // 连续天数 (合并月度复习+测验的活跃日)
        Set<String> activeDays = new HashSet<>(mReviewMap.keySet());
        activeDays.addAll(mQuizMap.keySet());
        int streak = calculateStreak(activeDays, dayFmt);

        return LearnerDashboardResponse.LearningRhythm.builder()
                .streakDays(streak)
                .weekly(weekly)
                .monthly(monthly)
                .build();
    }

    private List<LearnerDashboardResponse.DailyActivity> buildDailyActivities(
            int daysBack, Map<String, Long> reviewMap, Map<String, Long> quizMap, DateTimeFormatter dayFmt) {
        List<LearnerDashboardResponse.DailyActivity> result = new ArrayList<>();
        for (int i = daysBack; i >= 0; i--) {
            String day = LocalDate.now().minusDays(i).format(dayFmt);
            result.add(LearnerDashboardResponse.DailyActivity.builder()
                    .date(day.substring(5))
                    .reviews(reviewMap.getOrDefault(day, 0L))
                    .quizzes(quizMap.getOrDefault(day, 0L))
                    .build());
        }
        return result;
    }

    /**
     * 从 quiz_attempts 的 answer_details JSON 中提取错误题目和文档测验统计
     * 单次遍历完成两项工作，只查 answer_details/total_questions/correct_count/doc_id 四列
     */
    private void parseAnswerDetails(Long userId,
                                     Map<Long, Long> errorCountMap,
                                     Map<Long, long[]> docQuizMap) {
        List<QuizAttempt> attempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .select(QuizAttempt::getAnswerDetails, QuizAttempt::getDocId,
                                QuizAttempt::getTotalQuestions, QuizAttempt::getCorrectCount)
                        .eq(QuizAttempt::getUserId, userId)
                        .ge(QuizAttempt::getCreatedAt, LocalDateTime.now().minusDays(90)));
        for (QuizAttempt a : attempts) {
            // 文档测验统计
            if (a.getDocId() != null) {
                long[] stats = docQuizMap.computeIfAbsent(a.getDocId(), k -> new long[]{0, 0});
                stats[0] += a.getTotalQuestions() != null ? a.getTotalQuestions() : 0;
                stats[1] += a.getCorrectCount() != null ? a.getCorrectCount() : 0;
            }
            // 错误题目统计
            if (a.getAnswerDetails() != null) {
                try {
                    List<Map<String, Object>> details = objectMapper.readValue(
                            a.getAnswerDetails(), new TypeReference<>() {});
                    for (Map<String, Object> d : details) {
                        boolean correct = d.get("correct") instanceof Boolean b ? b
                                : "true".equals(String.valueOf(d.get("correct")));
                        if (!correct) {
                            long quizId = d.get("quizId") instanceof Number n ? n.longValue()
                                    : Long.parseLong(String.valueOf(d.get("quizId")));
                            errorCountMap.merge(quizId, 1L, Long::sum);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[学习者看板] 解析 answer_details 失败: attemptId={}", a.getId(), e);
                }
            }
        }
    }

    private List<LearnerDashboardResponse.ErrorItem> buildTopErrors(
            Map<Long, Long> errorCountMap, Map<Long, String> docNameMap) {
        List<Long> topErrorQuizIds = errorCountMap.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
        if (topErrorQuizIds.isEmpty()) return List.of();

        Map<Long, Quiz> quizMap = new HashMap<>();
        quizMapper.selectBatchIds(topErrorQuizIds).forEach(q -> quizMap.put(q.getId(), q));

        Map<Long, Long> quizDocMap = buildQuizDocMap(quizMap.values(), docNameMap);

        List<LearnerDashboardResponse.ErrorItem> result = new ArrayList<>();
        for (Long quizId : topErrorQuizIds) {
            Quiz quiz = quizMap.get(quizId);
            if (quiz == null) continue;
            Long docId = quizDocMap.get(quizId);
            result.add(LearnerDashboardResponse.ErrorItem.builder()
                    .quizId(quizId)
                    .question(quiz.getQuestion())
                    .answer(quiz.getAnswer())
                    .explanation(quiz.getExplanation())
                    .errorCount(errorCountMap.getOrDefault(quizId, 0L).intValue())
                    .docId(docId != null ? docId : 0)
                    .docName(docId != null ? docNameMap.getOrDefault(docId, "未知文档") : "未知文档")
                    .build());
        }
        return result;
    }

    private Map<Long, Long> buildQuizDocMap(Collection<Quiz> quizzes, Map<Long, String> docNameMap) {
        Map<Long, Long> quizDocMap = new HashMap<>();
        Set<Long> deckIds = quizzes.stream()
                .map(Quiz::getDeckId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!deckIds.isEmpty()) {
            List<CardDeck> decks = cardDeckMapper.selectBatchIds(deckIds);
            for (CardDeck deck : decks) {
                for (Quiz q : quizzes) {
                    if (Objects.equals(q.getDeckId(), deck.getId())) {
                        quizDocMap.put(q.getId(), deck.getDocId());
                    }
                }
            }
            Set<Long> docIds = decks.stream().map(CardDeck::getDocId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (!docIds.isEmpty()) docNameMap.putAll(fetchDocTitles(docIds));
        }
        return quizDocMap;
    }

    private List<LearnerDashboardResponse.DeckWeakness> buildDeckWeaknesses(
            Map<Long, long[]> docQuizMap, Map<Long, String> docNameMap) {
        if (docQuizMap.isEmpty()) return List.of();

        Set<Long> missingDocIds = docQuizMap.keySet().stream()
                .filter(id -> !docNameMap.containsKey(id)).collect(Collectors.toSet());
        if (!missingDocIds.isEmpty()) docNameMap.putAll(fetchDocTitles(missingDocIds));

        List<LearnerDashboardResponse.DeckWeakness> result = new ArrayList<>();
        for (var entry : docQuizMap.entrySet()) {
            long[] stats = entry.getValue();
            if (stats[0] == 0) continue;
            result.add(LearnerDashboardResponse.DeckWeakness.builder()
                    .docId(entry.getKey())
                    .docName(docNameMap.getOrDefault(entry.getKey(), "未知文档"))
                    .totalQuestions(stats[0])
                    .correctCount(stats[1])
                    .accuracyRate(Math.round(stats[1] * 1000.0 / stats[0]) / 10.0)
                    .build());
        }
        result.sort(Comparator.comparingDouble(LearnerDashboardResponse.DeckWeakness::getAccuracyRate));
        return result;
    }

    /**
     * 文档学习进度 — SQL GROUP BY doc_id 替代加载全部闪卡到 Java
     */
    private List<LearnerDashboardResponse.DocProgress> buildDocProgress(
            Long userId,
            List<LearnerDashboardResponse.DeckWeakness> deckWeaknesses,
            Map<Long, String> docNameMap) {

        List<Map<String, Object>> cardRows = flashcardMapper.countByDoc(userId);

        Map<Long, double[]> quizAccMap = new HashMap<>();
        for (var dw : deckWeaknesses) {
            quizAccMap.put(dw.getDocId(), new double[]{dw.getAccuracyRate(), dw.getTotalQuestions()});
        }

        Set<Long> allDocIds = new HashSet<>();
        for (Map<String, Object> row : cardRows) allDocIds.add(toLong(row, "docId"));
        allDocIds.addAll(quizAccMap.keySet());
        if (!allDocIds.isEmpty()) docNameMap.putAll(fetchDocTitles(allDocIds));

        List<LearnerDashboardResponse.DocProgress> result = new ArrayList<>();
        for (Map<String, Object> row : cardRows) {
            long docId = toLong(row, "docId");
            long totalCards = toLong(row, "total_cards");
            long reviewedCards = toLong(row, "reviewed_cards");
            double[] qStats = quizAccMap.getOrDefault(docId, new double[]{-1, 0});
            result.add(LearnerDashboardResponse.DocProgress.builder()
                    .docId(docId)
                    .docName(docNameMap.getOrDefault(docId, "未知文档"))
                    .totalCards(totalCards)
                    .reviewedCards(reviewedCards)
                    .quizAccuracy(qStats[0])
                    .quizTotal((long) qStats[1])
                    .build());
        }

        // 添加只有测验数据、没有闪卡的文档
        for (var entry : quizAccMap.entrySet()) {
            if (cardRows.stream().noneMatch(r -> toLong(r, "docId") == entry.getKey())) {
                double[] qStats = entry.getValue();
                result.add(LearnerDashboardResponse.DocProgress.builder()
                        .docId(entry.getKey())
                        .docName(docNameMap.getOrDefault(entry.getKey(), "未知文档"))
                        .totalCards(0).reviewedCards(0)
                        .quizAccuracy(qStats[0])
                        .quizTotal((long) qStats[1])
                        .build());
            }
        }

        result.sort((a, b) -> {
            if (a.getTotalCards() == 0 && b.getTotalCards() > 0) return 1;
            if (a.getTotalCards() > 0 && b.getTotalCards() == 0) return -1;
            return Long.compare(b.getTotalCards(), a.getTotalCards());
        });
        return result;
    }

    /**
     * 今日时间线 — 只查询今日数据，使用最小列集 (select)
     */
    private List<LearnerDashboardResponse.TimelineEntry> buildTimeline(
            Long userId, LocalDateTime todayStart, Map<Long, String> docNameMap) {
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        List<LearnerDashboardResponse.TimelineEntry> timeline = new ArrayList<>();

        // 闪卡复习: 只查今日的 flashcardId + createdAt，再批量解析 docId
        List<FlashcardReviewLog> todayReviews = flashcardReviewLogMapper.selectList(
                new LambdaQueryWrapper<FlashcardReviewLog>()
                        .select(FlashcardReviewLog::getFlashcardId, FlashcardReviewLog::getCreatedAt)
                        .eq(FlashcardReviewLog::getUserId, userId)
                        .ge(FlashcardReviewLog::getCreatedAt, todayStart));

        if (!todayReviews.isEmpty()) {
            Set<Long> fcIds = todayReviews.stream()
                    .map(FlashcardReviewLog::getFlashcardId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<Long, Flashcard> fcMap = new HashMap<>();
            if (!fcIds.isEmpty()) {
                flashcardMapper.selectBatchIds(fcIds).forEach(fc -> fcMap.put(fc.getId(), fc));
            }
            Map<Long, Object[]> reviewByDoc = new LinkedHashMap<>();
            for (FlashcardReviewLog r : todayReviews) {
                if (r.getFlashcardId() == null || r.getCreatedAt() == null) continue;
                Flashcard fc = fcMap.get(r.getFlashcardId());
                Long dId = fc != null ? fc.getDocId() : null;
                if (dId == null) continue;
                Object[] arr = reviewByDoc.computeIfAbsent(dId, k -> new Object[]{0, r.getCreatedAt()});
                arr[0] = ((int) arr[0]) + 1;
                if (r.getCreatedAt().isBefore((LocalDateTime) arr[1])) arr[1] = r.getCreatedAt();
            }
            for (var entry : reviewByDoc.entrySet()) {
                Object[] arr = entry.getValue();
                timeline.add(LearnerDashboardResponse.TimelineEntry.builder()
                        .time(((LocalDateTime) arr[1]).format(timeFmt))
                        .type("review")
                        .description(String.format("复习了 %d 张卡片", (int) arr[0]))
                        .docName(docNameMap.getOrDefault(entry.getKey(), "未知文档"))
                        .docId(entry.getKey())
                        .build());
            }
        }

        // 测验: 只查今日需要的列
        List<QuizAttempt> todayAttempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .select(QuizAttempt::getDeckId, QuizAttempt::getCreatedAt,
                                QuizAttempt::getTotalQuestions, QuizAttempt::getCorrectCount)
                        .eq(QuizAttempt::getUserId, userId)
                        .ge(QuizAttempt::getCreatedAt, todayStart));

        Set<Long> attemptDeckIds = todayAttempts.stream()
                .map(QuizAttempt::getDeckId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Long> attemptDeckDocMap = new HashMap<>();
        if (!attemptDeckIds.isEmpty()) {
            cardDeckMapper.selectBatchIds(attemptDeckIds)
                    .forEach(deck -> attemptDeckDocMap.put(deck.getId(), deck.getDocId()));
        }
        for (QuizAttempt a : todayAttempts) {
            if (a.getCreatedAt() == null) continue;
            Long dId = a.getDeckId() != null ? attemptDeckDocMap.get(a.getDeckId()) : null;
            String dName = dId != null ? docNameMap.getOrDefault(dId, "未知文档") : "未知文档";
            long total = a.getTotalQuestions() != null ? a.getTotalQuestions() : 0;
            long correct = a.getCorrectCount() != null ? a.getCorrectCount() : 0;
            timeline.add(LearnerDashboardResponse.TimelineEntry.builder()
                    .time(a.getCreatedAt().format(timeFmt))
                    .type("quiz")
                    .description(String.format("完成测验 %d/%d 题答对", correct, total))
                    .docName(dName)
                    .docId(dId != null ? dId : 0)
                    .build());
        }

        timeline.sort(Comparator.comparing(LearnerDashboardResponse.TimelineEntry::getTime));
        return timeline;
    }

    /**
     * 周度报告 — 复用已查的周度聚合数据 + 上周 SQL 聚合查询
     */
    private LearnerDashboardResponse.WeeklySummary buildWeeklySummary(
            Long userId, LocalDateTime todayStart,
            List<Map<String, Object>> weekReviewByDay,
            List<Map<String, Object>> weekQuizByDay,
            long weekTotalQ, long weekCorrect,
            Map<Long, String> docNameMap) {

        // 本周统计 (从已查的聚合数据中汇总)
        int weekReviews = sumCountFromDayRows(weekReviewByDay, null);
        Map<String, Long> wQuizMap = toDayMap(weekQuizByDay);
        int weekQuizCount = wQuizMap.values().stream().mapToInt(Long::intValue).sum();

        // 活跃天数
        Set<String> activeDays = new HashSet<>(toDayMap(weekReviewByDay).keySet());
        activeDays.addAll(wQuizMap.keySet());

        // 本周复习最多的文档
        LocalDateTime weekAgoTime = todayStart.minusDays(6);
        String topDocName = "";
        long topDocReviews = 0;
        List<FlashcardReviewLog> weekLogs = flashcardReviewLogMapper.selectList(
                new LambdaQueryWrapper<FlashcardReviewLog>()
                        .select(FlashcardReviewLog::getFlashcardId)
                        .eq(FlashcardReviewLog::getUserId, userId)
                        .ge(FlashcardReviewLog::getCreatedAt, weekAgoTime));
        if (!weekLogs.isEmpty()) {
            Set<Long> fcIds = weekLogs.stream()
                    .map(FlashcardReviewLog::getFlashcardId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<Long, Flashcard> fcMap = new HashMap<>();
            if (!fcIds.isEmpty()) {
                flashcardMapper.selectBatchIds(fcIds).forEach(fc -> fcMap.put(fc.getId(), fc));
            }
            Map<Long, Long> weekReviewByDoc = new HashMap<>();
            for (FlashcardReviewLog r : weekLogs) {
                if (r.getFlashcardId() == null) continue;
                Flashcard fc = fcMap.get(r.getFlashcardId());
                if (fc != null && fc.getDocId() != null) weekReviewByDoc.merge(fc.getDocId(), 1L, Long::sum);
            }
            if (!weekReviewByDoc.isEmpty()) {
                var topEntry = weekReviewByDoc.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElse(null);
                if (topEntry != null) {
                    docNameMap.putAll(fetchDocTitles(Set.of(topEntry.getKey())));
                    topDocName = docNameMap.getOrDefault(topEntry.getKey(), "未知文档");
                    topDocReviews = topEntry.getValue();
                }
            }
        }

        // 上周数据 (SQL 聚合，替代加载全部记录)
        LocalDateTime twoWeeksAgo = todayStart.minusDays(13);
        LocalDateTime lastWeekEnd = todayStart.minusDays(6);
        int prevWeekReviews = sumCountFromDayRows(
                flashcardReviewLogMapper.countByDayRange(userId, twoWeeksAgo, lastWeekEnd), null);
        int prevQuizCount = 0;
        long prevTotalQ = 0, prevCorrect = 0;
        for (Map<String, Object> row : quizAttemptMapper.countByDayRange(userId, twoWeeksAgo, lastWeekEnd)) {
            prevQuizCount += toInt(row, "cnt");
            prevTotalQ += toLong(row, "total");
            prevCorrect += toLong(row, "correct");
        }

        return LearnerDashboardResponse.WeeklySummary.builder()
                .reviews(weekReviews)
                .quizzes(weekQuizCount)
                .accuracy(weekTotalQ > 0 ? Math.round(weekCorrect * 1000.0 / weekTotalQ) / 10.0 : 0)
                .activeDays(activeDays.size())
                .topDocName(topDocName)
                .topDocReviews(topDocReviews)
                .prevReviews(prevWeekReviews)
                .prevQuizzes(prevQuizCount)
                .prevAccuracy(prevTotalQ > 0 ? Math.round(prevCorrect * 1000.0 / prevTotalQ) / 10.0 : 0)
                .build();
    }

    // ═══════ 工具方法 ═══════

    private Map<Long, String> fetchDocTitles(Set<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) return Map.of();
        Map<Long, String> map = new HashMap<>();
        documentMapper.selectBatchIds(docIds).forEach(doc -> map.put(doc.getId(), doc.getTitle()));
        return map;
    }

    private int calculateStreak(Set<String> activeDays, DateTimeFormatter dayFmt) {
        int streak = 0;
        for (int i = 0; i < 30; i++) {
            String day = LocalDate.now().minusDays(i).format(dayFmt);
            if (activeDays.contains(day)) streak++;
            else break;
        }
        return streak;
    }

    /** 将 SQL GROUP BY DATE 结果转为 day -> count 映射 */
    private Map<String, Long> toDayMap(List<Map<String, Object>> rows) {
        Map<String, Long> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object dayObj = row.get("day");
            if (dayObj == null) continue;
            map.put(dayObj.toString(), toLong(row, "cnt"));
        }
        return map;
    }

    /** 从 GROUP BY 结果汇总 count；targetDay=null 时汇总所有行 */
    private int sumCountFromDayRows(List<Map<String, Object>> rows, LocalDate targetDay) {
        if (targetDay == null) {
            int sum = 0;
            for (Map<String, Object> row : rows) sum += toInt(row, "cnt");
            return sum;
        }
        for (Map<String, Object> row : rows) {
            Object dayObj = row.get("day");
            if (dayObj != null && dayObj.toString().equals(targetDay.toString())) {
                return toInt(row, "cnt");
            }
        }
        return 0;
    }

    private int toInt(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    private long toLong(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return 0L; }
    }
}
