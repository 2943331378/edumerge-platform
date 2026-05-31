package com.edumerge.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 当前登录用户信息，由 JwtAuthFilter 注入 SecurityContext，
 * Controller 通过 getCurrentUserId() 静态方法获取。
 */
@Getter
@AllArgsConstructor
public class AuthUser {
    private Long userId;
    private String username;
}
