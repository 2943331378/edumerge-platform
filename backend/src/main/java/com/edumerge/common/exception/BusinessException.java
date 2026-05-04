package com.edumerge.common.exception;

import com.edumerge.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常基类
 * 所有业务异常都应继承此类
 */
@Getter
public class BusinessException extends RuntimeException {

    private Integer code;
    private String message;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = ResultCode.BUSINESS_ERROR.getCode();
        this.message = message;
    }

    public BusinessException(ResultCode resultCode, Throwable cause) {
        super(resultCode.getMessage(), cause);
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
    }
}
