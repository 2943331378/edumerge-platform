package com.edumerge.ai;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轻量级熔断器 — 防止 AI 模型 API 不可用时线程池耗尽导致服务雪崩。
 * <p>
 * 状态机：CLOSED → OPEN → HALF_OPEN → CLOSED (或回到 OPEN)
 */
@Slf4j
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMs;
    private final String name;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    public CircuitBreaker(String name, int failureThreshold, long cooldownMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    /**
     * 执行受熔断保护的操作。熔断开启时直接抛出异常，不调用操作。
     */
    public <T> T execute(java.util.function.Supplier<T> operation) {
        State currentState = state.get();

        if (currentState == State.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime.get() >= cooldownMs) {
                if (!state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    // CAS 失败: 另一个线程已转换状态，重新读取
                    currentState = state.get();
                    if (currentState == State.OPEN) {
                        throw new CircuitBreakerOpenException(name + " 熔断器开启中，请稍后重试");
                    }
                    // currentState 为 HALF_OPEN 或 CLOSED，放行
                } else {
                    log.info("[{}] 熔断器进入半开状态，尝试放行请求", name);
                }
            } else {
                throw new CircuitBreakerOpenException(name + " 熔断器开启中，请稍后重试");
            }
        }

        try {
            T result = operation.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        State prev = state.getAndSet(State.CLOSED);
        if (prev != State.CLOSED) {
            log.info("[{}] 熔断器恢复正常 (CLOSED)", name);
        }
    }

    private void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        if (failures >= failureThreshold) {
            state.set(State.OPEN);
            log.warn("[{}] 连续失败 {} 次，熔断器开启，冷却 {}s", name, failures, cooldownMs / 1000);
        }
    }

    public State getState() { return state.get(); }
    public int getConsecutiveFailures() { return consecutiveFailures.get(); }

    /** 熔断器开启时抛出的异常 */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) { super(message); }
    }
}
