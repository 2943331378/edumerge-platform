package com.edumerge.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * AI 思维导图生成器 (架构红线: LangChain4j 隔离在 ai 包)
 *
 * 核心流程: Milvus 检索非结构化文本块 → LLM 提取层级知识结构 → 输出 Markdown 格式思维导图
 * 体现"非结构化数据 → 结构化知识"的数据治理转化路径
 */
@Slf4j
@Service
public class AiMindMapGenerator extends AiGeneratorBase {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("contentChatModel")
    private ChatModel chatLanguageModel;

    @Autowired
    @Qualifier("streamingContentChatModel")
    private StreamingChatModel streamingModel;

    /**
     * 根据文档内容生成思维导图
     * @param docId   文档数据库 ID
     * @param docUuid 文档 Milvus UUID (用于向量检索)
     * @return 生成结果 (含 title、content)
     */
    public MindMapResult generate(Long docId, String docUuid, String sectionContext, Integer startChunk, Integer endChunk) {
        long startTime = System.currentTimeMillis();

        String context;
        int chunkCount;
        if (startChunk != null && endChunk != null) {
            context = buildContextFromRange(docId, startChunk, endChunk);
            if (context.isEmpty()) {
                log.warn("按 chunk 范围未获取到内容, 回退语义搜索: docId={}, range=[{},{}]", docId, startChunk, endChunk);
                List<EmbeddingMatch<TextSegment>> fallback = retrieveTopChunks(docUuid, 12,
                        "文档结构 章节标题 核心主题 关键概念 层级关系 目录大纲 主要内容");
                if (fallback.isEmpty()) { return MindMapResult.empty(); }
                context = buildContextWithPages(fallback);
                chunkCount = fallback.size();
            } else {
                chunkCount = endChunk - startChunk + 1;
            }
        } else {
            List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 12,
                    "文档结构 章节标题 核心主题 关键概念 层级关系 目录大纲 主要内容");
            if (matches.isEmpty()) {
                log.warn("未检索到文档块: docId={}, docUuid={}", docId, docUuid);
                return MindMapResult.empty();
            }
            context = buildContextWithPages(matches);
            chunkCount = matches.size();
        }
        String subjectRules = buildSubjectRules(getSubjectType(docId));
        log.info("思维导图上下文构建完成: docId={}, 块数={}, 耗时={}ms", docId, chunkCount, System.currentTimeMillis() - startTime);

        long llmStart = System.currentTimeMillis();
        String markdown = callLLM(context, sectionContext, subjectRules);
        log.info("LLM 思维导图生成完成: docId={}, 长度={}, LLM耗时={}ms", docId, markdown.length(), System.currentTimeMillis() - llmStart);

        markdown = cleanMarkdown(markdown);
        if (!isValidMindMap(markdown)) {
            log.error("思维导图格式验证失败: docId={}, content={}", docId, markdown.substring(0, Math.min(200, markdown.length())));
            return MindMapResult.empty();
        }

        String title = extractTitle(markdown);
        log.info("思维导图内容生成完成: docId={}, title={}", docId, title);
        return MindMapResult.success(title, markdown);
    }

    /**
     * 流式生成思维导图 — 逐 token 回调, 完成后返回完整结果
     */
    public MindMapResult generateStream(Long docId, String docUuid, String sectionContext,
                                         Integer startChunk, Integer endChunk, Consumer<String> onToken) {
        long startTime = System.currentTimeMillis();
        String context;
        if (startChunk != null && endChunk != null) {
            context = buildContextFromRange(docId, startChunk, endChunk);
            if (context.isEmpty()) {
                List<EmbeddingMatch<TextSegment>> fallback = retrieveTopChunks(docUuid, 12,
                        "文档结构 章节标题 核心主题 关键概念 层级关系 目录大纲 主要内容");
                if (fallback.isEmpty()) return MindMapResult.empty();
                context = buildContextWithPages(fallback);
            }
        } else {
            List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 12,
                    "文档结构 章节标题 核心主题 关键概念 层级关系 目录大纲 主要内容");
            if (matches.isEmpty()) return MindMapResult.empty();
            context = buildContextWithPages(matches);
        }
        String subjectRules = buildSubjectRules(getSubjectType(docId));
        log.info("流式思维导图上下文构建完成: docId={}, 耗时={}ms", docId, System.currentTimeMillis() - startTime);

        List<ChatMessage> messages = buildMindMapMessages(context, sectionContext, subjectRules);

        // 流式调用 LLM (DeepSeek 支持真正的流式回调)
        StringBuilder fullContent = new StringBuilder();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        long llmStart = System.currentTimeMillis();
        try {
            AI_CIRCUIT_BREAKER.execute(() -> {
                streamingModel.chat(messages, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        fullContent.append(token);
                        onToken.accept(token);
                    }
                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        log.info("流式思维导图 LLM 完成: docId={}, 长度={}, 耗时={}ms",
                                docId, fullContent.length(), System.currentTimeMillis() - llmStart);
                        latch.countDown();
                    }
                    @Override
                    public void onError(Throwable error) {
                        errorRef.set(error);
                        log.error("流式思维导图 LLM 错误: docId={}, error={}", docId, error.getMessage(), error);
                        latch.countDown();
                    }
                });
                try {
                    if (!latch.await(5, java.util.concurrent.TimeUnit.MINUTES)) {
                        log.error("流式思维导图 LLM 超时: docId={}", docId);
                        errorRef.set(new RuntimeException("LLM 调用超时"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errorRef.set(e);
                }
                if (errorRef.get() != null) throw new RuntimeException(errorRef.get());
                return null;
            });
        } catch (Exception e) {
            log.error("流式思维导图生成异常: docId={}, error={}", docId, e.getMessage(), e);
            String partial = cleanMarkdown(fullContent.toString());
            if (!partial.isBlank()) {
                String title = extractTitle(partial);
                return MindMapResult.success(title, partial);
            }
            return MindMapResult.empty();
        }

        String markdown = cleanMarkdown(fullContent.toString());
        if (!isValidMindMap(markdown)) {
            log.error("思维导图格式验证失败: docId={}, contentPreview={}", docId,
                    markdown.substring(0, Math.min(300, markdown.length())));
            return MindMapResult.empty();
        }
        String title = extractTitle(markdown);
        return MindMapResult.success(title, markdown);
    }

    private String callLLM(String context, String sectionContext, String subjectRules) {
        List<ChatMessage> messages = buildMindMapMessages(context, sectionContext, subjectRules);
        ChatResponse response = chatContent(chatLanguageModel, messages);
        return response.aiMessage().text();
    }

    private List<ChatMessage> buildMindMapMessages(String context, String sectionContext, String subjectRules) {
        String systemTemplate = """
                你是一个严谨的 AI 知识架构师，擅长从非结构化文本中提取层级知识结构。

                {COMMON_RULES}

                # 任务
                从文档中提取层级知识结构，输出 Markdown 思维导图。
                # = 中心主题（1个） ## = 主要分支（3-6个） ### = 子细节（每分支2-4个） - = 补充细节（可选）

                # 格式约束
                1. 仅输出 Markdown，禁止引导语/结束语
                2. 每个节点须为完整短语或句子，不可仅为单个词
                3. 层级间无空行，保持紧凑树状结构

                {SUBJECT_RULES}
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemTemplate
                .replace("{COMMON_RULES}", buildCommonRules())
                .replace("{SUBJECT_RULES}", subjectRules)));

        StringBuilder userSb = new StringBuilder();
        if (sectionContext != null && !sectionContext.isBlank()) {
            userSb.append("# 重点关注章节\n请重点围绕以下章节生成思维导图，但保持整体结构完整:\n").append(sectionContext.strip()).append("\n\n");
        }
        userSb.append("# 文档上下文\n").append(context).append("\n");
        userSb.append("请基于以上文档内容，生成一份结构清晰的 Markdown 思维导图。仅输出 Markdown 内容。");
        messages.add(new UserMessage(userSb.toString()));
        return messages;
    }

    /** 拼装上下文 — 标注片段来源以实现数据溯源 */
    private String buildContextWithPages(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            TextSegment seg = matches.get(i).embedded();
            sb.append("【片段").append(i + 1).append("】");
            String page = seg.metadata().getString("page_number");
            if (page != null && !page.isBlank()) {
                sb.append("(页码:").append(page).append(")");
            }
            sb.append("\n").append(seg.text()).append("\n\n");
        }
        return sb.toString();
    }

    /** 清理 LLM 输出: 去除引导语, 确保以 # 开头 */
    private String cleanMarkdown(String raw) {
        String trimmed = raw.trim();
        // 定位第一个 # 标题
        int hashIdx = trimmed.indexOf("# ");
        if (hashIdx == -1) hashIdx = trimmed.indexOf("#");
        if (hashIdx > 0) {
            // 去除 # 之前的引导文字
            trimmed = trimmed.substring(hashIdx);
        }
        return trimmed;
    }

    /** 验证: 必须至少包含一个 Markdown 标题 (# 或 ## 等) */
    private boolean isValidMindMap(String markdown) {
        if (markdown == null || markdown.isBlank()) return false;
        for (String line : markdown.split("\\n")) {
            if (line.trim().matches("^#{1,6}\\s.+")) return true;
        }
        return false;
    }

    /** 从 Markdown 提取第一个 # 标题 */
    private String extractTitle(String markdown) {
        if (markdown == null) return "思维导图";
        for (String line : markdown.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).replaceAll("[*`_~]", "").trim();
                return title.isEmpty() ? "思维导图" : title + " 导图";
            }
        }
        return "思维导图";
    }

    // ===== 结果封装 =====

    public static class MindMapResult {
        private final boolean success;
        private final String title;
        private final String content;

        private MindMapResult(boolean success, String title, String content) {
            this.success = success; this.title = title; this.content = content;
        }

        public static MindMapResult success(String title, String content) {
            return new MindMapResult(true, title, content);
        }

        public static MindMapResult empty() {
            return new MindMapResult(false, null, null);
        }

        public boolean isSuccess() { return success; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
    }
}
