package com.edumerge.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一返回对象
 * 所有接口都应该返回此对象进行包装，确保前后端交互的一致性
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /**
     * 业务状态码
     * 成功: 0
     * 业务异常: 4xx
     * 系统异常: 5xx
     */
    private Integer code;

    /**
     * 状态描述信息
     */
    private String message;

    /**
     * 返回的业务数据
     */
    private T data;

    /**
     * 成功响应 - 无数据
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 成功响应 - 带数据
     */
    public static <T> Result<T> success(T data) {
        return Result.<T>builder()
                .code(0)
                .message("SUCCESS")
                .data(data)
                .build();
    }

    /**
     * 成功响应 - 自定义消息
     */
    public static <T> Result<T> success(String message, T data) {
        return Result.<T>builder()
                .code(0)
                .message(message)
                .data(data)
                .build();
    }

    /**
     * 业务异常 - 使用 ResultCode 枚举
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode, null);
    }

    /**
     * 业务异常 - 使用 ResultCode 枚举 + 自定义数据
     */
    public static <T> Result<T> fail(ResultCode resultCode, T data) {
        return Result.<T>builder()
                .code(resultCode.getCode())
                .message(resultCode.getMessage())
                .data(data)
                .build();
    }

    /**
     * 业务异常 - 自定义消息
     */
    public static <T> Result<T> fail(String message) {
        return fail(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    /**
     * 业务异常 - 自定义状态码和消息
     */
    public static <T> Result<T> fail(Integer code, String message) {
        return Result.<T>builder()
                .code(code)
                .message(message)
                .build();
    }

    /**
     * 系统异常
     */
    public static <T> Result<T> error(String message) {
        return Result.<T>builder()
                .code(500)
                .message(message)
                .build();
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return code != null && code == 0;
    }
}
