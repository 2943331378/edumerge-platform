package com.edumerge.common.result;

import lombok.Getter;

/**
 * 业务状态码枚举
 * 便于管理和维护所有的业务异常状态码
 */
@Getter
public enum ResultCode {

    /* 成功 */
    SUCCESS(0, "操作成功"),

    /* 通用业务异常 4xx */
    BUSINESS_ERROR(400, "业务异常"),
    INVALID_PARAMETER(400, "参数异常"),
    MISSING_PARAMETER(400, "缺少必要参数"),
    INVALID_REQUEST(400, "非法请求"),
    NOT_FOUND(404, "资源不存在"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),

    /* 业务特定异常 */
    USER_NOT_EXIST(4001, "用户不存在"),
    INVALID_CREDENTIALS(4002, "用户名或密码错误"),
    USER_ALREADY_EXISTS(4003, "用户已存在"),
    DOCUMENT_NOT_FOUND(4004, "文档不存在"),
    INVALID_DOCUMENT_FORMAT(4005, "文档格式不支持"),
    EMBEDDING_FAILED(4006, "向量化失败"),
    RAG_QUERY_FAILED(4007, "RAG查询失败"),
    MODEL_API_ERROR(4008, "模型API调用失败"),

    /* 系统异常 5xx */
    SYSTEM_ERROR(500, "系统异常"),
    DATABASE_ERROR(5001, "数据库异常"),
    REDIS_ERROR(5002, "缓存异常"),
    MQ_ERROR(5003, "消息队列异常"),
    MILVUS_ERROR(5004, "向量数据库异常"),
    EXTERNAL_API_ERROR(5005, "外部API异常");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
