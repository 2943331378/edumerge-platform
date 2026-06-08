package com.edumerge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义线程池配置 — 用于文档处理流水线和通用异步任务
 */
@Configuration
public class ThreadPoolConfig {

    @Value("${app.async.core-pool-size:4}")
    private int asyncCorePoolSize;

    @Value("${app.async.max-pool-size:16}")
    private int asyncMaxPoolSize;

    @Value("${app.async.queue-capacity:100}")
    private int asyncQueueCapacity;

    /**
     * 文档处理专用线程池 — 用于并发向量化和异步大纲生成
     * 核心线程数=4, 最大线程数=8, 队列容量=32
     */
    @Bean("documentTaskExecutor")
    public ExecutorService documentTaskExecutor() {
        return new ThreadPoolExecutor(
                4, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                new NamedThreadFactory("doc-task"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 通用异步任务线程池 — 用于 SSE 流式对话等非文档处理的异步任务
     * 替代默认的 ForkJoinPool，避免文档处理任务与通用异步任务互相争抢线程
     */
    @Bean("asyncExecutor")
    public ExecutorService asyncExecutor() {
        return new ThreadPoolExecutor(
                asyncCorePoolSize, asyncMaxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(asyncQueueCapacity),
                new NamedThreadFactory("edumerge-async"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /** 带命名前缀的线程工厂，方便日志排查 */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
