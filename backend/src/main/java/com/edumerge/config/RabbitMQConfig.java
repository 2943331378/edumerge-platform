package com.edumerge.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * RabbitMQ 配置类
 * 定义消息队列、交换机、绑定关系
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    // ===== 向量化队列 (唯一活跃队列) =====
    /** 向量化任务队列 */
    public static final String EMBEDDING_QUEUE = "edumerge.embedding.queue";
    /** 向量化交换机 */
    public static final String EMBEDDING_EXCHANGE = "edumerge.embedding.exchange";
    /** 向量化 routing key */
    public static final String EMBEDDING_ROUTING_KEY = "embedding.task";

    /**
     * 配置 JSON 消息转换器
     * 使 RabbitMQ 可以自动序列化/反序列化 Java 对象
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置 RabbitTemplate - 提供发送消息的便利方法
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        // 启用消息发送确认
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData != null ? correlationData.getId() : "unknown";
            if (ack) {
                log.info("消息发送成功: id={}", id);
            } else {
                log.warn("消息发送失败: id={}, cause={}", id, cause);
            }
        });
        return rabbitTemplate;
    }

    // ===== 向量化队列配置 =====
    @Bean
    public Queue embeddingQueue() {
        return new Queue(EMBEDDING_QUEUE, true, false, false);
    }

    @Bean
    public TopicExchange embeddingExchange() {
        return new TopicExchange(EMBEDDING_EXCHANGE, true, false);
    }

    @Bean
    public Binding embeddingBinding(Queue embeddingQueue, TopicExchange embeddingExchange) {
        return org.springframework.amqp.core.BindingBuilder
                .bind(embeddingQueue)
                .to(embeddingExchange)
                .with(EMBEDDING_ROUTING_KEY);
    }

    /**
     * 配置重试模板 - 用于消息发送失败重试
     */
    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        
        // 重试策略：最多重试 3 次
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        retryTemplate.setRetryPolicy(retryPolicy);
        
        // 退避策略：指数退避，初始延迟 1s，最大延迟 10s，倍数 2
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000);
        backOffPolicy.setMaxInterval(10000);
        backOffPolicy.setMultiplier(2.0);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        
        return retryTemplate;
    }
}
