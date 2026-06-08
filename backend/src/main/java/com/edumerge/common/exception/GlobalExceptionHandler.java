package com.edumerge.common.exception;

import com.edumerge.ai.CircuitBreaker;
import com.edumerge.common.result.Result;
import com.edumerge.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理所有 REST 接口抛出的异常，确保返回格式一致
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常 (BusinessException)
     * 业务逻辑中主动抛出的异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 处理认证异常 (AuthenticationException)
     * SecurityUtils.getCurrentUserId() 未认证时抛出
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<?>> handleAuthenticationException(AuthenticationException e) {
        log.warn("认证异常: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期，请重新登录"));
    }

    /**
     * 处理 HTTP 状态码异常 (ResponseStatusException)
     * 如 verifyOwnership() 抛出的 403
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> handleResponseStatusException(ResponseStatusException e) {
        log.warn("HTTP 异常: status={}, message={}", e.getStatusCode(), e.getReason());
        return ResponseEntity
                .status(e.getStatusCode())
                .body(Result.fail(e.getStatusCode().value(), e.getReason() != null ? e.getReason() : "请求错误"));
    }

    /**
     * 处理熔断器异常 (CircuitBreakerOpenException)
     * AI 模型 API 连续失败时触发
     */
    @ExceptionHandler(CircuitBreaker.CircuitBreakerOpenException.class)
    public ResponseEntity<Result<?>> handleCircuitBreakerOpenException(CircuitBreaker.CircuitBreakerOpenException e) {
        log.warn("熔断器异常: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail(503, "AI 服务暂时不可用，请稍后重试"));
    }

    /**
     * 处理参数验证异常 (MethodArgumentNotValidException)
     * 在 @Valid 验证不通过时触发
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String errorMessage = bindingResult.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        log.warn("参数验证异常: {}", errorMessage);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.INVALID_PARAMETER.getCode(), errorMessage));
    }

    /**
     * 处理类型不匹配异常 (MethodArgumentTypeMismatchException)
     * 如：期望 Integer 但收到 String
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String errorMessage = String.format("参数类型错误: %s 应为 %s", 
                e.getName(), e.getRequiredType().getSimpleName());
        log.warn(errorMessage);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.INVALID_PARAMETER.getCode(), errorMessage));
    }

    /**
     * 处理请求体不可读异常 (HttpMessageNotReadableException)
     * JSON 格式错误、枚举值不合法等
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体不可读: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.INVALID_REQUEST.getCode(), "请求体格式错误，请检查参数"));
    }

    /**
     * 处理请求方法不支持异常 (HttpRequestMethodNotSupportedException)
     * 如 GET 请求访问了仅支持 POST 的端点
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        String errorMessage = String.format("不支持的请求方法: %s，支持: %s",
                e.getMethod(), e.getSupportedHttpMethods());
        log.warn(errorMessage);
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(405, errorMessage));
    }

    /**
     * 处理 404 异常 (NoHandlerFoundException)
     * 当请求的路由不存在时触发
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<?>> handleNoHandlerFoundException(NoHandlerFoundException e) {
        String errorMessage = String.format("请求的资源不存在: %s %s", e.getHttpMethod(), e.getRequestURL());
        log.warn(errorMessage);
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ResultCode.NOT_FOUND.getCode(), errorMessage));
    }

    /**
     * 处理 IllegalArgumentException
     * 通常由非法参数触发
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数异常: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.INVALID_PARAMETER.getCode(),
                        e.getMessage() != null ? e.getMessage() : "非法参数"));
    }

    /**
     * 处理 NullPointerException
     * 空指针异常（不应该在生产环境出现）
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Result<?>> handleNullPointerException(NullPointerException e) {
        log.error("空指针异常", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error("系统异常: 空指针"));
    }

    /**
     * 处理文件上传大小超限异常
     * 当上传文件超过 spring.servlet.multipart.max-file-size 时触发
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("文件上传超限: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.fail("文件大小超过限制（最大 50MB），请压缩后重试"));
    }

    /**
     * 处理其他所有异常 (Throwable)
     * 作为兜底方案，捕获所有未被特殊处理的异常
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Result<?>> handleThrowable(Throwable e) {
        log.error("未知异常", e);
        String message = e.getMessage() != null ? e.getMessage() : "系统异常，请稍后重试";
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(message));
    }
}
