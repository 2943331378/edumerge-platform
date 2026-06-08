package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.dto.LearnerDashboardResponse;
import com.edumerge.entity.*;
import com.edumerge.mapper.*;
import com.edumerge.security.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
    public LearnerDashboardResponse calculate() {
        Long userId = SecurityUtils.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime weekAgo = todayStart.minusDays(6);
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // ===== 今日待办 =====
        List<FlashcardReviewLog> todayReviews = flashcardReviewLogMapper.selectList(
                new LambdaQueryWrapper<FlashcardReviewLog>()
                        .eq(FlashcardReviewLog::getUserId, userId)
                        .ge(FlashcardReviewLog::getCreatedAt, todayStart));
        List<QuizAttempt> todayAttempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getUserId, userId)
                        .ge(QuizAttempt::getCreatedAt, todayStart));
        long todayTotalQ = todayAttempts.stream()
                .mapToLong(a -> a.getTotalQuestions() != null ? a.getTotalQuestions() : 0).sum();
        long todayCorrect = todayAttempts.stream()
                .mapToLong(a -> a.getCorrectCount() != null ? a.getCorrectCount() : 0).sum();

        LearnerDashboardResponse.TodayTasks today = LearnerDashboardResponse.TodayTasks.builder()
                .reviewedCards(todayReviews.size())
                .quizAttempts(todayAttempts.size())
                .quizAccuracy(todayTotalQ > 0 ? Math.round(todayCorrect * 1000.0 / todayTotalQ) / 10.0 : 0)
                .correctCount(todayCorrect)
                .totalAnswered(todayTotalQ)
                .build();

        // ===== 到期闪卡 =====
        List<Flashcard> dueCards = flashcardMapper.selectList(
                new LambdaQueryWrapper<Flashcard>()
                        .eq(Flashcard::getUserId, userId)
                        .eq(Flashcard::getStatus, "ACTIVE")
                        .eq(Flashcard::getDeleted, 0)
                        .and(w -> w
                                .le(Flashcard::getNextReviewAt, now)
                                .or()
                                .isNull(Flashcard::getNextReviewAt)));
        List<LearnerDashboardResponse.DocDueInfo> dueDocs = buildDueDocs(dueCards);

        // ===== 月度数据（用于节奏 + 成就） =====
        List<FlashcardReviewLog> monthReviews = flashcardReviewLogMapper.selectList(
                new LambdaQueryWrapper<FlashcardReviewLog>()
                        .eq(FlashcardReviewLog::getUserId, userId)
                        .ge(FlashcardReviewLog::getCreatedAt, todayStart.minusDays(29)));
        List<QuizAttempt> monthAttempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getUserId, userId)
                        .ge(QuizAttempt::getCreatedAt, todayStart.minusDays(29)));

        Set<String> activeDays = collectActiveDays(monthReviews, monthAttempts);
        int streak = calculateStreak(activeDays, dayFmt);

        // ===== 周度数据 =====
        List<FlashcardReviewLog> weekReviews = flashcardReviewLogMapper.selectList(
                new LambdaQueryWrapper<FlashcardReviewLog>()
                        .eq(FlashcardReviewLog::getUserId, userId)
                        .ge(FlashcardReviewLog::getCreatedAt, weekAgo));
        List<QuizAttempt> weekAttempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getUserId, userId)
                        .ge(QuizAttempt::getCreatedAt, weekAgo));

        // 7 天趋势 + 30 天热力图
        LearnerDashboardResponse.LearningRhythm rhythm = buildRhythm(
                streak, weekReviews, weekAttempts, monthReviews, monthAttempts, dayFmt);

        // ===== 累计成就 =====
        long totalReviews = flashcardReviewLogMapper.selectCount(
                new LambdaQueryWrapper<FlashcardReviewLog>()
                        .eq(FlashcardReviewLog::getUserId, userId));
        List<QuizAttempt> allAttempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getUserId, userId));
        long allTotalQ = allAttempts.stream()
                .mapToLong(a -> a.getTotalQuestions() != null ? a.getTotalQuestions() : 0).sum();
        long allCorrect = allAttempts.stream()
                .mapToLong(a -> a.getCorrectCount() != null ? a.getCorrectCount() : 0).sum();

        long totalDocs = documentMapper.selectCount(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId)
                        .eq(Document::getDeleted, 0));
        long totalFlashcards = flashcardMapper.selectCount(
                new LambdaQueryWrapper<Flashcard>()
                        .eq(Flashcard::getUserId, userId)
                        .eq(Flashcard::getDeleted, 0));
        long totalQuizQuestions = quizMapper.selectCount(
                new LambdaQueryWrapper<Quiz>()
                        .eq(Quiz::getUserId, userId));

        LearnerDashboardResponse.Achievement achievement = LearnerDashboardResponse.Achievement.builder()
                .totalReviews(totalReviews)
                .totalQuizzes(allAttempts.size())
                .avgAccuracy(allTotalQ > 0 ? Math.round(allCorrect * 1000.0 / allTotalQ) / 10.0 : 0)
                .totalDocs(totalDocs)
                .totalFlashcards(totalFlashcards)
                .totalQuizQuestions(totalQuizQuestions)
                .build();

        // ===== 薄弱知识点 =====
        Map<Long, String> docNameMap = new HashMap<>();
        Map<Long, int[]> errorCountMap = new LinkedHashMap<>();
        Map<Long, long[]> docQuizMap = new LinkedHashMap<>();
        parseAnswerDetails(allAttempts, errorCountMap, docQuizMap);

        List<LearnerDashboardResponse.ErrorItem> topErrors = buildTopErrors(errorCountMap, docNameMap);
        List<LearnerDashboardResponse.DeckWeakness> deckWeaknesses = buildDeckWeaknesses(docQuizMap, docNameMap);

        // ===== 文档学习进度 =====
        List<LearnerDashboardResponse.DocProgress> docProgress = buildDocProgress(
                userId, docQuizMap, deckWeaknesses, docNameMap);

        // ===== 今日时间线 =====
        List<LearnerDashboardResponse.TimelineEntry> timeline = buildTimeline(
                todayReviews, todayAttempts, docNameMap, dayFmt);

        // ===== 周度报告 =====
        LearnerDashboardResponse.WeeklySummary weeklySummary = buildWeeklySummary(
                userId, weekReviews, weekAttempts, todayStart, dayFmt, docNameMap);

        log.info("[学习者看板] userId={}, 待复习={}, 连续{}天, 累计复习={}, 正确率={}%",
                userId, dueCards.size(), streak, totalReviews, achievement.getAvgAccuracy());

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

    private List<LearnerDashboardResponse.DocDueInfo> buildDueDocs(List<Flashcard> dueCards) {
        Map<Long, Long> dueByDoc = dueCards.stream()
                .filter(f -> f.getDocId() != null)
                .collect(Collectors.groupingBy(Flashcard::getDocId, Collectors.counting()));
        if (dueByDoc.isEmpty()) return List.of();

        Map<Long, String> titleMap = new HashMap<>();
        documentMapper.selectBatchIds(dueByDoc.keySet())
                .forEach(doc -> titleMap.put(doc.getId(), doc.getTitle()));

        List<LearnerDashboardResponse.DocDueInfo> result = new ArrayList<>();
        for (var entry : dueByDoc.entrySet()) {
            result.add(LearnerDashboardResponse.DocDueInfo.builder()
                    .docId(entry.getKey())
                    .docName(titleMap.getOrDefault(entry.getKey(), "未知文档"))
                    .dueCount(entry.getValue())
                    .build());
        }
        result.sort((a, b) -> Long.compare(b.getDueCount(), a.getDueCount()));
        return result;
    }

    private LearnerDashboardResponse.LearningRhythm buildRhythm(
            int streak,
            List<FlashcardReviewLog> weekReviews, List<QuizAttempt> weekAttempts,
            List<FlashcardReviewLog> monthReviews, List<QuizAttempt> monthAttempts,
            DateTimeFormatter dayFmt) {

        // 7 天趋势
        Map<String, Long> reviewByDay = initDayMap(6, dayFmt);
        Map<String, Long> quizByDay = initDayMap(6, dayFmt);
        for (FlashcardReviewLog r : weekReviews) {
            if (r.getCreatedAt() != null) reviewByDay.merge(r.getCreatedAt().toLocalDate().format(dayFmt), 1L, Long::sum);
        }
        for (QuizAttempt a : weekAttempts) {
            if (a.getCreatedAt() != null) quizByDay.merge(a.getCreatedAt().toLocalDate().format(dayFmt), 1L, Long::sum);
        }
        List<LearnerDashboardResponse.DailyActivity> weekly = new ArrayList<>();
        for (String day : reviewByDay.keySet()) {
            weekly.add(LearnerDashboardResponse.DailyActivity.builder()
                    .date(day.substring(5))
                    .reviews(reviewByDay.getOrDefault(day, 0L))
                    .quizzes(quizByDay.getOrDefault(day, 0L))
                    .build());
        }

        // 30 天热力图
        Map<String, Long> monthReviewByDay = initDayMap(29, dayFmt);
        Map<String, Long> monthQuizByDay = initDayMap(29, dayFmt);
        for (FlashcardReviewLog r : monthReviews) {
            if (r.getCreatedAt() != null) monthReviewByDay.merge(r.getCreatedAt().toLocalDate().format(dayFmt), 1L, Long::sum);
        }
        for (QuizAttempt a : monthAttempts) {
            if (a.getCreatedAt() != null) monthQuizByDay.merge(a.getCreatedAt().toLocalDate().format(dayFmt), 1L, Long::sum);
        }
        List<LearnerDashboardResponse.DailyActivity> monthly = new ArrayList<>();
        for (String day : monthReviewByDay.keySet()) {
            monthly.add(LearnerDashboardResponse.DailyActivity.builder()
                    .date(day.substring(5))
                    .reviews(monthReviewByDay.getOrDefault(day, 0L))
                    .quizzes(monthQuizByDay.getOrDefault(day, 0L))
                    .build());
        }

        return LearnerDashboardResponse.LearningRhythm.builder()
                .streakDays(streak)
                .weekly(weekly)
                .monthly(monthly)
                .build();
    }

    private void parseAnswerDetails(List<QuizAttempt> allAttempts,
                                     Map<Long, int[]> errorCountMap,
                                     Map<Long, long[]> docQuizMap) {
        for (QuizAttempt a : allAttempts) {
            if (a.getAnswerDetails() != null) {
                try {
                    List<Map<String, Object>> details = objectMapper.readValue(
                            a.getAnswerDetails(), new TypeReference<>() {});
                    for (Map<String, Object> d : details) {
                        boolean correct = d.get("correct") instanceof Boolean b ? b
                                : "true".equals(String.valueOf(d.get("correct")));
                        long quizId = d.get("quizId") instanceof Number n ? n.longValue()
                                : Long.parseLong(String.valueOf(d.get("quizId")));
                        if (!correct) {
                            errorCountMap.computeIfAbsent(quizId, k -> new int[]{0});
                            errorCountMap.get(quizId)[0]++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[学习者看板] 解析 answer_details 失败: attemptId={}", a.getId(), e);
                }
            }
            if (a.getDocId() != null) {
                docQuizMap.computeIfAbsent(a.getDocId(), k -> new long[]{0, 0});
                long[] stats = docQuizMap.get(a.getDocId());
                stats[0] += a.getTotalQuestions() != null ? a.getTotalQuestions() : 0;
                stats[1] += a.getCorrectCount() != null ? a.getCorrectCount() : 0;
            }
        }
    }

    private List<LearnerDashboardResponse.ErrorItem> buildTopErrors(
            Map<Long, int[]> errorCountMap, Map<Long, String> docNameMap) {
        List<Long> topErrorQuizIds = errorCountMap.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
        if (topErrorQuizIds.isEmpty()) return List.of();

        Map<Long, Quiz> quizMap = new HashMap<>();
        quizMapper.selectBatchIds(topErrorQuizIds).forEach(q -> quizMap.put(q.getId(), q));

        // quizId → docId 映射
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
                    .errorCount(errorCountMap.get(quizId)[0])
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
            // 补充 docNameMap
            Set<Long> docIds = decks.stream().map(CardDeck::getDocId).filter(Objects::nonNull).collect(Collectors.toSet());
            if (!docIds.isEmpty()) {
                documentMapper.selectBatchIds(docIds)
                        .forEach(doc -> docNameMap.put(doc.getId(), doc.getTitle()));
            }
        }
        return quizDocMap;
    }

    private List<LearnerDashboardResponse.DeckWeakness> buildDeckWeaknesses(
            Map<Long, long[]> docQuizMap, Map<Long, String> docNameMap) {
        // 补充 docNameMap
        Set<Long> missingDocIds = docQuizMap.keySet().stream()
                .filter(id -> !docNameMap.containsKey(id)).collect(Collectors.toSet());
        if (!missingDocIds.isEmpty()) {
            documentMapper.selectBatchIds(missingDocIds)
                    .forEach(doc -> docNameMap.put(doc.getId(), doc.getTitle()));
        }

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

    private List<LearnerDashboardResponse.DocProgress> buildDocProgress(
            Long userId, Map<Long, long[]> docQuizMap,
            List<LearnerDashboardResponse.DeckWeakness> deckWeaknesses,
            Map<Long, String> docNameMap) {
        List<Flashcard> userFlashcards = flashcardMapper.selectList(
                new LambdaQueryWrapper<Flashcard>()
                        .eq(Flashcard::getUserId, userId)
                        .eq(Flashcard::getDeleted, 0));
        Map<Long, long[]> cardProgressMap = new LinkedHashMap<>();
        for (Flashcard f : userFlashcards) {
            if (f.getDocId() == null) continue;
            long[] stats = cardProgressMap.computeIfAbsent(f.getDocId(), k -> new long[]{0, 0});
            stats[0]++;
            if (f.getReviewCount() != null && f.getReviewCount() > 0) stats[1]++;
        }

        Set<Long> progressDocIds = new HashSet<>(cardProgressMap.keySet());
        progressDocIds.addAll(docQuizMap.keySet());
        for (Long id : progressDocIds) {
            if (!docNameMap.containsKey(id)) {
                Document d = documentMapper.selectById(id);
                if (d != null) docNameMap.put(id, d.getTitle());
            }
        }

        Map<Long, double[]> quizAccMap = new HashMap<>();
        for (var dw : deckWeaknesses) {
            quizAccMap.put(dw.getDocId(), new double[]{dw.getAccuracyRate(), dw.getTotalQuestions()});
        }

        List<LearnerDashboardResponse.DocProgress> result = new ArrayList<>();
        for (Long dId : progressDocIds) {
            long[] cardStats = cardProgressMap.getOrDefault(dId, new long[]{0, 0});
            double[] qStats = quizAccMap.getOrDefault(dId, new double[]{-1, 0});
            result.add(LearnerDashboardResponse.DocProgress.builder()
                    .docId(dId)
                    .docName(docNameMap.getOrDefault(dId, "未知文档"))
                    .totalCards(cardStats[0])
                    .reviewedCards(cardStats[1])
                    .quizAccuracy(qStats[0])
                    .quizTotal((long) qStats[1])
                    .build());
        }
        result.sort((a, b) -> {
            if (a.getTotalCards() == 0 && b.getTotalCards() > 0) return 1;
            if (a.getTotalCards() > 0 && b.getTotalCards() == 0) return -1;
            return Long.compare(b.getTotalCards(), a.getTotalCards());
        });
        return result;
    }

    private List<LearnerDashboardResponse.TimelineEntry> buildTimeline(
            List<FlashcardReviewLog> todayReviews, List<QuizAttempt> todayAttempts,
            Map<Long, String> docNameMap, DateTimeFormatter dayFmt) {
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        List<LearnerDashboardResponse.TimelineEntry> timeline = new ArrayList<>();

        // 闪卡复习：按文档分组
        if (!todayReviews.isEmpty()) {
            Set<Long> fcIds = todayReviews.stream()
                    .map(FlashcardReviewLog::getFlashcardId).filter(Objects::nonNull).collect(Collectors.toSet());
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

        // 测验
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

    private LearnerDashboardResponse.WeeklySummary buildWeeklySummary(
            Long userId,
            List<FlashcardReviewLog> weekReviews, List<QuizAttempt> weekAttempts,
            LocalDateTime todayStart, DateTimeFormatter dayFmt,
            Map<Long, String> docNameMap) {
        long weekTotalQ = weekAttempts.stream()
                .mapToLong(a -> a.getTotalQuestions() != null ? a.getTotalQuestions() : 0).sum();
        long weekCorrect = weekAttempts.stream()
                .mapToLong(a -> a.getCorrectCount() != null ? a.getCorrectCount() : 0).sum();

        // 活跃天数
        Map<String, Long> reviewByDay = initDayMap(6, dayFmt);
        Map<String, Long> quizByDay = initDayMap(6, dayFmt);
        for (FlashcardReviewLog r : weekReviews) {
            if (r.getCreatedAt() != null) reviewByDay.merge(r.getCreatedAt().toLocalDate().format(dayFmt), 1L, Long::sum);
        }
        for (QuizAttempt a : weekAttempts) {
            if (a.getCreatedAt() != null) quizByDay.merge(a.getCreatedAt().toLocalDate().format(dayFmt), 1L, Long::sum);
        }
        int weekActiveDays = 0;
        for (String day : reviewByDay.keySet()) {
            if (reviewByDay.getOrDefault(day, 0L) > 0 || quizByDay.getOrDefault(day, 0L) > 0) weekActiveDays++;
        }

        // 本周复习最多的文档
        Map<Long, Long> weekReviewByDoc = new HashMap<>();
        if (!weekReviews.isEmpty()) {
            Set<Long> fcIds = weekReviews.stream()
                    .map(FlashcardReviewLog::getFlashcardId).filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, Flashcard> fcMap = new HashMap<>();
            if (!fcIds.isEmpty()) {
                flashcardMapper.selectBatchIds(fcIds).forEach(fc -> fcMap.put(fc.getId(), fc));
            }
            for (FlashcardReviewLog r : weekReviews) {
                if (r.getFlashcardId() == null) continue;
                Flashcard fc = fcMap.get(r.getFlashcardId());
                if (fc != null && fc.getDocId() != null) weekReviewByDoc.merge(fc.getDocId(), 1L, Long::sum);
            }
        }
        String topDocName = "";
        long topDocReviews = 0;
        if (!weekReviewByDoc.isEmpty()) {
            var topEntry = weekReviewByDoc.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (topEntry != null) {
                topDocName = docNameMap.getOrDefault(topEntry.getKey(), "未知文档");
                topDocReviews = topEntry.getValue();
            }
        }

        // 上周数据
        LocalDateTime twoWeeksAgo = todayStart.minusDays(13);
        LocalDateTime lastWeekEnd = todayStart.minusDays(6);
        List<FlashcardReviewLog> prevWeekReviews = flashcardReviewLogMapper.selectList(
                new LambdaQueryWrapper<FlashcardReviewLog>()
                        .eq(FlashcardReviewLog::getUserId, userId)
                        .ge(FlashcardReviewLog::getCreatedAt, twoWeeksAgo)
                        .lt(FlashcardReviewLog::getCreatedAt, lastWeekEnd));
        List<QuizAttempt> prevWeekAttempts = quizAttemptMapper.selectList(
                new LambdaQueryWrapper<QuizAttempt>()
                        .eq(QuizAttempt::getUserId, userId)
                        .ge(QuizAttempt::getCreatedAt, twoWeeksAgo)
                        .lt(QuizAttempt::getCreatedAt, lastWeekEnd));
        long prevTotalQ = prevWeekAttempts.stream()
                .mapToLong(a -> a.getTotalQuestions() != null ? a.getTotalQuestions() : 0).sum();
        long prevCorrect = prevWeekAttempts.stream()
                .mapToLong(a -> a.getCorrectCount() != null ? a.getCorrectCount() : 0).sum();

        return LearnerDashboardResponse.WeeklySummary.builder()
                .reviews(weekReviews.size())
                .quizzes(weekAttempts.size())
                .accuracy(weekTotalQ > 0 ? Math.round(weekCorrect * 1000.0 / weekTotalQ) / 10.0 : 0)
                .activeDays(weekActiveDays)
                .topDocName(topDocName)
                .topDocReviews(topDocReviews)
                .prevReviews(prevWeekReviews.size())
                .prevQuizzes(prevWeekAttempts.size())
                .prevAccuracy(prevTotalQ > 0 ? Math.round(prevCorrect * 1000.0 / prevTotalQ) / 10.0 : 0)
                .build();
    }

    // ═══════ 工具方法 ═══════

    private Set<String> collectActiveDays(List<FlashcardReviewLog> reviews, List<QuizAttempt> attempts) {
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Set<String> activeDays = new HashSet<>();
        for (FlashcardReviewLog r : reviews) {
            if (r.getCreatedAt() != null) activeDays.add(r.getCreatedAt().toLocalDate().format(dayFmt));
        }
        for (QuizAttempt a : attempts) {
            if (a.getCreatedAt() != null) activeDays.add(a.getCreatedAt().toLocalDate().format(dayFmt));
        }
        return activeDays;
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

    private Map<String, Long> initDayMap(int daysBack, DateTimeFormatter dayFmt) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = daysBack; i >= 0; i--) {
            map.put(LocalDate.now().minusDays(i).format(dayFmt), 0L);
        }
        return map;
    }
}
