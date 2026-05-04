package com.edumerge.controller;

import com.edumerge.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查接口
 * 用于监控和负载均衡
 */
@Slf4j
@RestController
@RequestMapping("/health")
public class HealthCheckController {

    /**
     * 简单的健康检查端点
     * GET /api/health/ping
     */
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("PONG");
    }

    /**
     * 详细的健康检查端点
     * GET /api/health/status
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> statusMap = new HashMap<>();
        statusMap.put("status", "UP");
        statusMap.put("timestamp", System.currentTimeMillis());
        statusMap.put("version", "1.0.0");
        statusMap.put("service", "EduMerge Backend");
        
        return Result.success("Health check passed", statusMap);
    }

    /**
     * 获取系统信息
     * GET /api/health/info
     */
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        Map<String, Object> infoMap = new HashMap<>();
        infoMap.put("java.version", System.getProperty("java.version"));
        infoMap.put("os.name", System.getProperty("os.name"));
        infoMap.put("available.processors", Runtime.getRuntime().availableProcessors());
        infoMap.put("total.memory.mb", Runtime.getRuntime().totalMemory() / (1024 * 1024));
        infoMap.put("free.memory.mb", Runtime.getRuntime().freeMemory() / (1024 * 1024));
        
        return Result.success("System information", infoMap);
    }
}
