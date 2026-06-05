package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.LearnerDashboardResponse;
import com.edumerge.dto.LearningStatsResponse;
import com.edumerge.dto.StatsResponse;
import com.edumerge.service.StatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据资产看板 API — 2026 大数据要素素质大赛
 *
 * 提供数据治理成果的量化视图，将非结构化数据的转化过程
 * 以结构化指标呈现，支撑大赛评审对"数据要素价值"的评估。
 */
@Slf4j
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final StatsService statsService;

    @Autowired
    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * GET /api/stats — 数据资产全维度指标
     * 返回非结构化数据处理规模、结构化知识转化成果、合规治理状态
     * 以及自动化 RAG 评测指标 (由 evaluate_rag.py 推送)
     */
    @GetMapping
    public Result<StatsResponse> stats() {
        log.info("[数据看板] 计算数据资产指标...");
        StatsResponse resp = statsService.calculate();
        return Result.success("数据资产指标计算完成", resp);
    }

    /**
     * GET /api/stats/report — 数据素质自评报告 (Markdown)
     * 可直接下载或复制至大赛项目书
     */
    @GetMapping("/report")
    public Result<Map<String, String>> report() {
        log.info("[数据看板] 生成数据素质自评报告...");
        String markdown = statsService.generateReport();
        return Result.success(Map.of(
                "format", "markdown",
                "title", "EduMerge 数据素质自评报告",
                "content", markdown
        ));
    }

    /**
     * GET /api/stats/learning — 个人学习行为统计
     * 今日复习/测验、近 7 天趋势、累计统计、连续学习天数
     */
    @GetMapping("/learning")
    public Result<LearningStatsResponse> learningStats() {
        LearningStatsResponse resp = statsService.calculateLearningStats();
        return Result.success(resp);
    }

    /**
     * GET /api/stats/learner — 学习者个人中心看板
     * 聚合学习者真正关心的数据：待办任务、学习节奏、累计成就
     */
    @GetMapping("/learner")
    public Result<LearnerDashboardResponse> learnerDashboard() {
        LearnerDashboardResponse resp = statsService.calculateLearnerDashboard();
        return Result.success(resp);
    }

    /**
     * POST /api/stats/eval — 接收评测脚本推送的 RAG 评测指标
     * 实现"评测-看板-报告"全链路数据闭环，确保看板展示的
     * 是实时、可验证的 AI 质量指标。
     */
    @PostMapping("/eval")
    public Result<String> updateEvalMetrics(@RequestBody Map<String, Object> body) {
        log.info("[数据看板] 接收评测指标推送: hitRate={}, faithfulness={}, correctness={}",
                body.get("hitRate"), body.get("avgFaithfulness"), body.get("avgCorrectness"));
        statsService.updateEvalMetrics(
                toDouble(body.get("hitRate")),
                toDouble(body.get("avgFaithfulness")),
                toDouble(body.get("avgCorrectness")),
                toDouble(body.get("compositeScore")),
                toInt(body.get("totalQuestions"))
        );
        return Result.success("评测指标已更新");
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) return Double.parseDouble(s);
        return 0.0;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) return Integer.parseInt(s);
        return 0;
    }
}
