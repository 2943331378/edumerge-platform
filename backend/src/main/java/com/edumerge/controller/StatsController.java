package com.edumerge.controller;

import com.edumerge.common.result.Result;
import com.edumerge.dto.StatsResponse;
import com.edumerge.service.StatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
