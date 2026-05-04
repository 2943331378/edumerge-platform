package com.edumerge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 用户创建请求 DTO（示例）
 * 用于接收前端 POST 请求的参数
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateUserRequest {

    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 邮箱
     */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 密码
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 显示名称
     */
    @NotBlank(message = "显示名称不能为空")
    private String displayName;
}
