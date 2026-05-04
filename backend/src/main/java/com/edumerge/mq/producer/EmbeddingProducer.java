package com.edumerge.mq.producer;

import com.edumerge.config.RabbitMQConfig;
import com.edumerge.mq.message.DocumentProcessMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 向量化任务生产者 — 用户上传文件后发送消息到向量化队列
 */
@Slf4j
@Component
public class EmbeddingProducer {

    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public EmbeddingProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送文档向量化任务
     *
     * @return true 表示消息已发出, false 表示 RabbitMQ 不可用
     */
    public boolean sendEmbeddingTask(String documentId, String filePath, String fileName, Long userId) {
        DocumentProcessMessage message = new DocumentProcessMessage(documentId, filePath, fileName, userId);

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EMBEDDING_EXCHANGE,
                    RabbitMQConfig.EMBEDDING_ROUTING_KEY,
                    message
            );
            log.info("向量化任务已发送: {}", message);
            return true;
        } catch (Exception e) {
            log.error("发送向量化任务失败 (RabbitMQ 不可用): documentId={}, error={}", documentId, e.getMessage());
            return false;
        }
    }
}
