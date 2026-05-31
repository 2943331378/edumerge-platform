package com.edumerge.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 数据审计配置 — 注册审计拦截器到 AI 生成类接口
 *
 * 【大赛数据治理能力】
 * 对以下"非结构化数据→结构化知识"转化链路实施全覆盖审计:
 *   - RAG 对话 (/rag/chat, /chat/stream)
 *   - 学习笔记生成 (/notes/generate)
 *   - 思维导图生成 (/mindmap)
 *   - 闪卡生成 (/flashcards/generate)
 *   - 测验生成 (/quizzes/generate)
 */
@Slf4j
@Configuration
public class AuditConfig implements WebMvcConfigurer {

    /** AI 生成类接口路径模式 — 数据要素治理的覆盖范围 */
    private static final String[] AUDIT_PATH_PATTERNS = {
            "/rag/**",
            "/chat/**",
            "/notes/generate",
            "/mindmap**",
            "/flashcards/generate",
            "/quizzes/generate",
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuditTimingInterceptor())
                .addPathPatterns(AUDIT_PATH_PATTERNS)
                .order(1);
    }

    /**
     * 轻量级请求审计拦截器 — 记录请求元数据与耗时
     * 配合 DataAuditInterceptor (ResponseBodyAdvice) 形成完整的
     * "请求入→内容审→响应出"数据审计闭环
     */
    static class AuditTimingInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                 Object handler) {
            request.setAttribute("audit.startTime", System.currentTimeMillis());
            log.info("[审计] 请求进入: {} {} (IP={})",
                    request.getMethod(), request.getRequestURI(),
                    request.getRemoteAddr());
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception ex) {
            Long startTime = (Long) request.getAttribute("audit.startTime");
            long duration = startTime != null ? System.currentTimeMillis() - startTime : -1;
            log.info("[审计] 请求完成: {} {} → {} (耗时={}ms)",
                    request.getMethod(), request.getRequestURI(),
                    response.getStatus(), duration);
        }
    }
}
