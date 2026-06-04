package com.edumerge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义线程池配置 — 用于文档处理流水线中的并发任务
 */
@Configuration
public class ThreadPoolConfig {

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
