package com.edumerge.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 提取当前登录用户信息的工具类。
 * 未认证时抛出异常，防止以默认用户身份执行操作。
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static Long getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser authUser) {
            return authUser.getUserId();
        }
        throw new AuthenticationCredentialsNotFoundException("用户未认证，无法获取当前用户 ID");
    }

    public static String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser authUser) {
            return authUser.getUsername();
        }
        throw new AuthenticationCredentialsNotFoundException("用户未认证，无法获取当前用户名");
    }
}
