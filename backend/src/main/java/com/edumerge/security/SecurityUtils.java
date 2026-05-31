package com.edumerge.security;

import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 提取当前登录用户信息的工具类。
 * 未登录时返回默认值 (userId=1L) 以兼容开发环境。
 */
public final class SecurityUtils {

    /** 未登录时的默认 userId — 仅用于向后兼容 */
    private static final Long DEFAULT_USER_ID = 1L;

    private SecurityUtils() {}

    public static Long getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser authUser) {
            return authUser.getUserId();
        }
        return DEFAULT_USER_ID;
    }

    public static String getCurrentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser authUser) {
            return authUser.getUsername();
        }
        return "admin";
    }
}
