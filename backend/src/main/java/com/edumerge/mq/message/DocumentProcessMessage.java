package com.edumerge.mq.message;

import java.io.Serializable;

/**
 * 文档处理消息 — 由生产者发送到 RabbitMQ 向量化队列
 */
public class DocumentProcessMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String documentId;
    private String filePath;
    private String fileName;
    private Long userId;

    public DocumentProcessMessage() {}

    public DocumentProcessMessage(String documentId, String filePath, String fileName, Long userId) {
        this.documentId = documentId;
        this.filePath = filePath;
        this.fileName = fileName;
        this.userId = userId;
    }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    @Override
    public String toString() {
        return "DocumentProcessMessage{documentId='" + documentId + "', fileName='" + fileName + "'}";
    }
}
