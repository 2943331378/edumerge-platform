package com.edumerge.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AiNoteGenerator extends AiGeneratorBase {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("contentChatModel")
    private ChatModel chatLanguageModel;

    public StudyNoteResult generate(Long docId, String docUuid, String requirements, String sectionContext, Integer startChunk, Integer endChunk) {
        long startTime = System.currentTimeMillis();

        String context;
        List<EmbeddingMatch<TextSegment>> matches;
        if (startChunk != null && endChunk != null) {
            context = buildContextFromRange(docId, startChunk, endChunk);
            if (context.isEmpty()) {
                log.warn("按 chunk 范围未获取到内容, 回退语义搜索: docId={}, range=[{},{}]", docId, startChunk, endChunk);
                matches = retrieveTopChunks(docUuid, 15,
                        "学习笔记 摘要 总结 章节要点 核心概念 关键知识点 方法 原理 辨析 易错点 对比 应用场景");
                if (matches.isEmpty()) { return StudyNoteResult.empty(); }
                context = buildContext(matches);
            } else {
                matches = List.of();
            }
        } else {
            matches = retrieveTopChunks(docUuid, 15,
                    "学习笔记 摘要 总结 章节要点 核心概念 关键知识点 方法 原理 辨析 易错点 对比 应用场景");
            if (matches.isEmpty()) {
                log.warn("未检索到文档块: docId={}, docUuid={}", docId, docUuid);
                return StudyNoteResult.empty();
            }
            context = buildContext(matches);
        }
        String subjectRules = buildSubjectRules(getSubjectType(docId));
        log.info("笔记上下文构建完成: docId={}, 块数={}, 检索耗时={}ms", docId, matches.size(), System.currentTimeMillis() - startTime);

        long llmStart = System.currentTimeMillis();
        String content = cleanMarkdown(callLLM(context, requirements, sectionContext, subjectRules));
        log.info("LLM 笔记生成完成: docId={}, 长度={}, LLM耗时={}ms", docId, content.length(), System.currentTimeMillis() - llmStart);
        if (content.isBlank()) {
            return StudyNoteResult.empty();
        }

        // 从 Markdown 提取第一个 # 标题作为笔记标题，持久化由 StudyNoteService 负责
        String title = extractTitle(content);
        String sourceSummary = buildSourceSummary(matches);
        return StudyNoteResult.success(title, content, sourceSummary, requirements);
    }

    private String callLLM(String context, String requirements, String sectionContext, String subjectRules) {
        String template = """
                你是一个严谨的 AI 学习笔记助手。请基于文档片段，生成一份适合学生备考复习的 Markdown 学习笔记。

                {COMMON_RULES}

                {SUBJECT_RULES}

                # 输出格式
                仅输出 Markdown，必须包含以下标题:
                # 中文学习笔记
                ## 文档概述（100-200字）
                ## 核心知识点（5-8条，每条含原理说明和适用条件，而非仅列定义）
                ## 关键概念辨析（4-6组易混淆概念的对比分析，用表格或并列说明区别与联系）
                ## 典型应用场景（每个知识点在什么条件下用、怎么用、举具体例子）
                ## 易错点与注意事项（常见的错误理解、边界条件、特殊情况）
                ## 复习清单（可勾选Markdown任务列表，按优先级排列）
                ## 可自测问题（5个问题，考察理解深度而非记忆，不附答案）

                # 写作约束
                - 内容面向备考学生，重点是"理解"和"会用"，不是罗列知识点
                - 每个知识点都要说明"为什么"和"什么时候用"，不要只说"是什么"
                - 不要引用片段编号作为正文标题

                {REQUIREMENTS}
                # 文档上下文
                {CONTEXT}
                """;

        String reqSection = (requirements != null && !requirements.isBlank())
                ? "# 用户个性化要求\n请特别注意用户的以下要求：" + requirements.strip() + "\n\n"
                : "";
        String sectionHint = (sectionContext != null && !sectionContext.isBlank())
                ? "# 重点关注章节\n请重点围绕以下章节生成笔记，但保持整体结构完整：" + sectionContext.strip() + "\n\n"
                : "";

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(template
                .replace("{COMMON_RULES}", buildCommonRules())
                .replace("{SUBJECT_RULES}", subjectRules)
                .replace("{REQUIREMENTS}", reqSection + sectionHint)
                .replace("{CONTEXT}", context)));
        messages.add(new UserMessage("请基于以上文档内容生成一份结构化中文学习笔记。"));
        ChatResponse response = chatContent(chatLanguageModel, messages);
        return response.aiMessage().text();
    }

    private String cleanMarkdown(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int heading = trimmed.indexOf("# ");
        return heading > 0 ? trimmed.substring(heading).trim() : trimmed;
    }

    /** 从 Markdown 内容中提取第一个 # 标题 */
    private String extractTitle(String markdown) {
        if (markdown == null) return "学习笔记";
        for (String line : markdown.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).trim();
                // 去除 Markdown 格式符号
                title = title.replaceAll("[*`_~]", "").trim();
                return title.isEmpty() ? "学习笔记" : title;
            }
        }
        return "学习笔记";
    }

    private String buildSourceSummary(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            TextSegment segment = matches.get(i).embedded();
            sb.append("[片段").append(i + 1).append("] ");
            String text = segment.text().replaceAll("\\s+", " ");
            sb.append(truncate(text, 180)).append("\n");
        }
        return sb.toString().trim();
    }

    public static class StudyNoteResult {
        private final boolean success;
        private final String title;
        private final String content;
        private final String sourceSummary;
        private final String requirements;

        private StudyNoteResult(boolean success, String title, String content,
                                String sourceSummary, String requirements) {
            this.success = success;
            this.title = title;
            this.content = content;
            this.sourceSummary = sourceSummary;
            this.requirements = requirements;
        }

        public static StudyNoteResult success(String title, String content,
                                              String sourceSummary, String requirements) {
            return new StudyNoteResult(true, title, content, sourceSummary, requirements);
        }

        public static StudyNoteResult empty() {
            return new StudyNoteResult(false, null, null, null, null);
        }

        public boolean isSuccess() { return success; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getSourceSummary() { return sourceSummary; }
        public String getRequirements() { return requirements; }
    }
}
