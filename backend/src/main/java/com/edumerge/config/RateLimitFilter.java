package com.edumerge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 IP 的 API 限流过滤器 — 滑动窗口计数器
 *
 * 保护敏感端点免受暴力破解和恶意刷接口：
 * - /auth/login: 10 次/分钟 (防暴力破解)
 * - /auth/register: 5 次/分钟 (防批量注册)
 * - 全局默认: 120 次/分钟 (防 DDoS)
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** IP → 滑动窗口状态 */
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /** 端点限流配置 */
    private static final Map<String, RateLimit> ENDPOINT_LIMITS = Map.of(
            "/auth/login", new RateLimit(10, 60_000),
            "/auth/register", new RateLimit(5, 60_000)
    );

    /** 全局默认限流 */
    private static final RateLimit GLOBAL_LIMIT = new RateLimit(120, 60_000);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = getClientIp(request);
        String path = request.getRequestURI();

        // 去掉 context-path 前缀匹配
        String endpoint = path.startsWith("/api") ? path.substring(4) : path;

        // 查找端点特定限流或使用全局默认
        RateLimit limit = ENDPOINT_LIMITS.getOrDefault(endpoint, GLOBAL_LIMIT);

        WindowCounter counter = counters.computeIfAbsent(clientIp + ":" + getLimitKey(endpoint),
                k -> new WindowCounter(limit.windowMs));

        if (!counter.tryAcquire(limit.maxRequests)) {
            log.warn("[限流] IP={} 触发限流: {} ({}次/{}秒)", clientIp, endpoint,
                    limit.maxRequests, limit.windowMs / 1000);
            sendTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只过滤 API 请求，放行静态资源和 actuator
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith("/api/actuator");
    }

    private String getLimitKey(String endpoint) {
        for (String key : ENDPOINT_LIMITS.keySet()) {
            if (endpoint.startsWith(key)) return key;
        }
        return "_global";
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private void sendTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", 429,
                "message", "请求过于频繁，请稍后再试"
        ));
    }

    /** 滑动窗口计数器 */
    static class WindowCounter {
        private final long windowMs;
        private volatile long windowStart;
        private final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long windowMs) {
            this.windowMs = windowMs;
            this.windowStart = System.currentTimeMillis();
        }

        boolean tryAcquire(int maxRequests) {
            long now = System.currentTimeMillis();
            // 窗口过期则重置
            if (now - windowStart >= windowMs) {
                synchronized (this) {
                    if (now - windowStart >= windowMs) {
                        windowStart = now;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet() <= maxRequests;
        }
    }

    record RateLimit(int maxRequests, long windowMs) {}
}
