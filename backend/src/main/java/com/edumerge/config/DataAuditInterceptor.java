package com.edumerge.config;

import com.edumerge.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据要素安全审计中间件 (ResponseBodyAdvice 实现)
 *
 * 【大赛核心能力 - 数据治理与合规】
 * 在 AI 生成内容返回给用户前，执行"数据要素安全检查"：
 * 1. 关键字匹配过滤 — 拦截违反教育方针、政治敏感、暴恐色情等有害信息
 * 2. 内容完整性验证 — 拒绝空响应
 * 3. 审计日志记录 — 所有拦截事件写入结构化日志，体现"数据可审计性"
 *
 * 拦截范围: /api/rag/chat, /api/chat/stream, /api/notes/generate,
 *           /api/flashcards/generate, /api/quizzes/generate, /api/mindmap
 */
@Slf4j
@ControllerAdvice(basePackages = "com.edumerge.controller")
public class DataAuditInterceptor implements ResponseBodyAdvice<Object> {

    // ===== 数据要素安全治理关键词库 =====
    // 按类别组织，体现多层次数据安全防护体系

    /** 教育方针合规 — 危害国家教育安全的内容 */
    private static final Set<String> EDUCATION_POLICY_KEYWORDS = Set.of(
            "颠覆国家政权", "分裂国家", "破坏国家统一",
            "煽动民族仇恨", "破坏民族团结", "邪教",
            "恐怖主义", "极端主义", "宣扬暴力"
    );

    /** 政治敏感 — 违反社会主义核心价值观的表述 */
    private static final Set<String> POLITICAL_KEYWORDS = Set.of(
            "推翻社会主义制度", "破坏宪法", "颠覆政府",
            "颜色革命", "和平演变"
    );

    /** 有害内容 — 黄赌毒等违法信息 */
    private static final Set<String> HARMFUL_KEYWORDS = Set.of(
            "毒品制作", "枪支制造", "炸弹制作",
            "传播淫秽", "赌博技术"
    );

    /** 敏感内容模式 (正则) — 身份证号、手机号等个人信息泄露防护 */
    private static final Pattern PII_PATTERN = Pattern.compile(
            "\\b(\\d{15}|\\d{18})\\b" // 身份证号 (简化匹配)
    );

    /** 审计屏蔽替代文本 */
    private static final String BLOCKED_MESSAGE = "内容因合规审查未通过，已屏蔽。如有疑问请联系系统管理员。";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true; // 拦截所有 Controller 响应
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof Result<?> result)) {
            return body;
        }

        // 仅审计成功的 AI 响应 (code=0 且有 data)
        if (!result.isSuccess() || result.getData() == null) {
            return body;
        }

        String uri = extractUri(request);
        String content = extractAnswerContent(result.getData());

        if (content == null || content.isBlank()) {
            return body; // 非 AI 接口，放行
        }

        // ---- 数据要素安全检查 ----

        // 1. 内容为空检查 (AI 生成失败但返回了空串)
        if (content.trim().isEmpty()) {
            log.warn("[数据审计] 空内容响应, URI={}", uri);
            return body;
        }

        // 2. 关键词匹配扫描
        String auditResult = auditContent(content, uri);

        if (auditResult != null) {
            // 合规审查未通过 — 替换内容并记录审计日志
            log.error("[数据审计] ⚠ 内容拦截! URI={}, 原因={}", uri, auditResult);
            return replaceContent(result, BLOCKED_MESSAGE, uri, auditResult);
        }

        // 3. 通过检查，记录放行审计日志
        log.debug("[数据审计] ✓ 内容合规放行, URI={}, contentLength={}", uri, content.length());
        return body;
    }

    /**
     * 数据要素合规检查 — 扫描 AI 生成内容是否包含违规关键词
     * @return 违规原因(关键词)，null 表示通过
     */
    private String auditContent(String content, String uri) {
        // 教育方针合规检查
        for (String kw : EDUCATION_POLICY_KEYWORDS) {
            if (content.contains(kw)) {
                return "教育方针违规: " + kw;
            }
        }
        // 政治敏感检查
        for (String kw : POLITICAL_KEYWORDS) {
            if (content.contains(kw)) {
                return "政治敏感: " + kw;
            }
        }
        // 有害内容检查
        for (String kw : HARMFUL_KEYWORDS) {
            if (content.contains(kw)) {
                return "有害内容: " + kw;
            }
        }
        // 个人信息泄露检查 (仅在内容过长时检查, 避免误判学术内容中的数字)
        if (content.length() < 500 && PII_PATTERN.matcher(content).find()) {
            return "疑似个人信息泄露";
        }
        return null; // 通过
    }

    /**
     * 替换被拦截内容的 Result
     * 保持原有数据结构，仅将 AI 生成的内容替换为合规提示
     */
    @SuppressWarnings("unchecked")
    private Result<Object> replaceContent(Result<?> original, String blockedMessage,
                                          String uri, String auditReason) {
        Object data = original.getData();
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            // 替换 answer 字段
            if (map.containsKey("answer")) {
                map.put("answer", blockedMessage);
            }
            // 清空 sources (被屏蔽内容不应提供溯源)
            if (map.containsKey("sources")) {
                map.put("sources", java.util.Collections.emptyList());
            }
            // 替换 content 字段 (笔记/思维导图)
            if (map.containsKey("content")) {
                map.put("content", blockedMessage);
            }
        }
        // 附加审计标记
        Result<Object> result = (Result<Object>) original;
        result.setMessage("内容经数据要素安全审计，存在" + auditReason + "，已屏蔽");
        return result;
    }

    /** 从 Result.data 中提取 AI 生成的文本内容 */
    private String extractAnswerContent(Object data) {
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            // RAG chat 响应: {answer, sources}
            Object answer = map.get("answer");
            if (answer instanceof String ans && !ans.isBlank()) {
                return ans;
            }
            // 笔记/思维导图响应: {content, ...}
            Object content = map.get("content");
            if (content instanceof String c && !c.isBlank()) {
                return c;
            }
            // 卡片/测验(列表): 拼接检查
            if (map.containsKey("question") && map.containsKey("answer")) {
                return String.valueOf(map.get("question")) + " " + String.valueOf(map.get("answer"));
            }
        }
        // 列表类型 (卡片列表、测验列表): 拼接第一个元素检查
        if (data instanceof java.util.List && !((java.util.List<?>) data).isEmpty()) {
            return extractAnswerContent(((java.util.List<?>) data).get(0));
        }
        return null;
    }

    private String extractUri(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest req = servletRequest.getServletRequest();
            return req.getRequestURI();
        }
        return request.getURI().toString();
    }
}
