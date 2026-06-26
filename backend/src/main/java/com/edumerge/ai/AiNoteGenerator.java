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

@Slf4j
@Service
public class AiNoteGenerator extends AiGeneratorBase {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("contentChatModel")
    private ChatModel chatLanguageModel;

    @Autowired
    @Qualifier("streamingContentChatModel")
    private StreamingChatModel streamingModel;

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
        String subjectGuide = buildNoteSubjectGuide(getSubjectType(docId));
        log.info("笔记上下文构建完成: docId={}, 块数={}, 检索耗时={}ms", docId, matches.size(), System.currentTimeMillis() - startTime);

        long llmStart = System.currentTimeMillis();
        String content = cleanMarkdown(callLLM(context, requirements, sectionContext, subjectGuide));
        log.info("LLM 笔记生成完成: docId={}, 长度={}, LLM耗时={}ms", docId, content.length(), System.currentTimeMillis() - llmStart);
        if (content.isBlank()) {
            return StudyNoteResult.empty();
        }

        // 从 Markdown 提取第一个 # 标题作为笔记标题，持久化由 StudyNoteService 负责
        String title = extractTitle(content);
        String sourceSummary = buildSourceSummary(matches);
        return StudyNoteResult.success(title, content, sourceSummary, requirements);
    }

    /**
     * 流式生成笔记 — 逐 token 回调, 完成后返回完整结果
     * @param onToken 每收到一个 token 片段时回调
     * @return 生成结果 (含 title, content, sourceSummary)
     */
    public StudyNoteResult generateStream(Long docId, String docUuid, String requirements,
                                           String sectionContext, Integer startChunk, Integer endChunk,
                                           Consumer<String> onToken) {
        long startTime = System.currentTimeMillis();
        String context;
        List<EmbeddingMatch<TextSegment>> matches;
        if (startChunk != null && endChunk != null) {
            context = buildContextFromRange(docId, startChunk, endChunk);
            if (context.isEmpty()) {
                matches = retrieveTopChunks(docUuid, 15,
                        "学习笔记 摘要 总结 章节要点 核心概念 关键知识点 方法 原理 辨析 易错点 对比 应用场景");
                if (matches.isEmpty()) return StudyNoteResult.empty();
                context = buildContext(matches);
            } else {
                matches = List.of();
            }
        } else {
            matches = retrieveTopChunks(docUuid, 15,
                    "学习笔记 摘要 总结 章节要点 核心概念 关键知识点 方法 原理 辨析 易错点 对比 应用场景");
            if (matches.isEmpty()) return StudyNoteResult.empty();
            context = buildContext(matches);
        }
        String subjectGuide = buildNoteSubjectGuide(getSubjectType(docId));
        log.info("流式笔记上下文构建完成: docId={}, 块数={}, 耗时={}ms", docId, matches.size(), System.currentTimeMillis() - startTime);

        // 流式调用 LLM (DeepSeek 支持真正的流式回调)
        List<ChatMessage> messages = buildNoteMessages(context, requirements, sectionContext, subjectGuide);

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
                        log.info("流式笔记 LLM 完成: docId={}, 长度={}, 耗时={}ms",
                                docId, fullContent.length(), System.currentTimeMillis() - llmStart);
                        latch.countDown();
                    }
                    @Override
                    public void onError(Throwable error) {
                        errorRef.set(error);
                        log.error("流式笔记 LLM 错误: docId={}, error={}", docId, error.getMessage(), error);
                        latch.countDown();
                    }
                });
                try {
                    if (!latch.await(5, java.util.concurrent.TimeUnit.MINUTES)) {
                        log.error("流式笔记 LLM 超时: docId={}", docId);
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
            log.error("流式笔记生成异常: docId={}, error={}", docId, e.getMessage(), e);
            String partial = cleanMarkdown(fullContent.toString());
            if (!partial.isBlank()) {
                String title = extractTitle(partial);
                String sourceSummary = buildSourceSummary(matches);
                return StudyNoteResult.success(title, partial, sourceSummary, requirements);
            }
            return StudyNoteResult.empty();
        }

        String content = cleanMarkdown(fullContent.toString());
        if (content.isBlank()) return StudyNoteResult.empty();

        String title = extractTitle(content);
        String sourceSummary = buildSourceSummary(matches);
        return StudyNoteResult.success(title, content, sourceSummary, requirements);
    }

    /** 构建笔记生成的消息列表 (同步/流式共用) */
    private List<ChatMessage> buildNoteMessages(String context, String requirements,
                                                  String sectionContext, String subjectGuide) {
        String systemTemplate = """
                你是一个顶尖的学术导师和认知科学家。你的任务是基于文档片段，生成一份**高信息密度、具备深度理解**的 Markdown 学习笔记。

                {COMMON_RULES}

                {SUBJECT_NOTE_GUIDE}

                # 核心工作流（隐式思考，无需输出）
                1. **分析文本特征**：判断当前内容属于哪种"知识类型"（概念解析型、流程机制型、对比辨析型、推导证明型）。
                2. **选择输出策略**：根据知识类型和学科指引，动态调整下方各模块的篇幅和侧重点。如果某模块在当前文本中完全不适用（如纯理论推导没有"典型应用场景"），请将其替换为"理论延伸"或"前置知识依赖"，**严禁编造废话**。

                # 输出格式（仅输出 Markdown）
                # [提炼一个极具吸引力的笔记标题，不超过15字]

                ## 核心摘要与知识锚点
                - **一句话总结**：（用费曼技巧，通俗易懂地解释这段内容的核心本质，50字以内）
                - **前置依赖**：（理解本内容需要提前掌握什么概念？若无则写"无"）
                - **后续延伸**：（本内容为哪些高级知识做铺垫？若无则写"无"）

                ## 底层逻辑与核心机制
                *(本部分是笔记的灵魂。拒绝罗列定义，必须讲透"为什么"和"怎么运作")*
                - 针对每个核心概念，按以下结构展开：
                  - **概念本质**：（专业定义 + 大白话类比解释）
                  - **运作机制/推导过程**：（详细说明原理、步骤或公式推导）
                  - **条件与边界**：（在什么前提下成立？什么情况下会失效？）

                ## 知识可视化 (Mermaid)
                *(按需生成，强调信息增量。如果文本逻辑简单，无需强行画图)*
                - **触发条件**：仅当内容包含复杂的流程流转、状态变化、层级分类或对象关系时生成。
                - **图表类型选择**：流程图(graph)、状态图(stateDiagram-v2)、时序图(sequenceDiagram)、类图(classDiagram)。
                - **Mermaid 语法铁律（违反会导致渲染崩溃）**：
                  1. 节点文本必须用双引号包裹：A["说明文字"]，禁止裸露特殊字符。
                  2. 边标签禁止使用引号：A -->|标签文字| B。
                  3. subgraph 标题禁止括号：subgraph 标题名称。
                  4. 节点 ID 只用英文字母和数字：A、B、node1，禁止中文 ID。
                *(在此处输出 ```mermaid 代码块，并在图表前后各加一行说明文字)*

                ## 深度辨析与避坑指南
                *(根据文本内容，选择以下 1-2 个最合适的维度展开，不要全选)*
                - **易混淆概念对比**：（使用 Markdown 表格，对比 2-3 组极易混淆的概念，列出核心差异点）
                - **常见误区与 Bug 陷阱**：（指出初学者最容易犯的错误、错误理解或代码 Bug）
                - **方案选型对比**：（如果文档涉及多种方法/算法/技术，对比其优劣和适用场景）

                ## 场景映射与实战应用
                *(将理论落地。如果文本是纯理论/纯数学，此节可替换为"经典例题解析"或"定理几何直觉")*
                - **典型应用场景**：（在什么具体条件下使用？解决什么实际问题？）
                - **实战案例/代码片段**：（给出一个具体的例子或核心代码片段，并附带逐行/逐步解析）

                ## 费曼自测清单
                *(生成 3-5 个能检验"是否真正理解"的启发式问题，不附答案)*
                - [ ] 问题1：（例如：如果改变X条件，Y结果会发生什么变化？为什么？）
                - [ ] 问题2：（例如：你能用生活中的例子向非专业人士解释Z概念吗？）
                - [ ] 问题3：（例如：A方案和B方案在极端情况下的表现有何不同？）

                # 写作铁律
                1. **认知降维**：遇到极其抽象的概念，必须尝试提供一个"生活中的类比"。
                2. **信息密度**：删除所有"众所周知"、"如前所述"等废话，每一句话都要有信息量。
                3. **排版美学**：合理使用加粗（**核心术语**）、高亮、列表和引用块（>）来引导视觉焦点。
                4. **严禁编造**：如果文档中没有提供足够的信息来支撑某个模块，请直接省略该模块或替换为与文本强相关的其他分析维度。
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemTemplate
                .replace("{COMMON_RULES}", buildCommonRules())
                .replace("{SUBJECT_NOTE_GUIDE}", subjectGuide)));

        StringBuilder userSb = new StringBuilder();
        if (requirements != null && !requirements.isBlank()) {
            userSb.append("# 用户个性化要求\n请特别注意用户的以下要求：").append(requirements.strip()).append("\n\n");
        }
        if (sectionContext != null && !sectionContext.isBlank()) {
            userSb.append("# 重点关注章节\n请重点围绕以下章节生成笔记，但保持整体结构完整：").append(sectionContext.strip()).append("\n\n");
        }
        userSb.append("# 文档上下文\n").append(context).append("\n");
        userSb.append("请深呼吸，一步步思考，基于以上文档内容生成一份高质量、结构化的中文学习笔记。");
        messages.add(new UserMessage(userSb.toString()));
        return messages;
    }

    private String callLLM(String context, String requirements, String sectionContext, String subjectGuide) {
        List<ChatMessage> messages = buildNoteMessages(context, requirements, sectionContext, subjectGuide);
        ChatResponse response = chatContent(chatLanguageModel, messages);
        return response.aiMessage().text();
    }

    private String cleanMarkdown(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        // 仅剥离 LLM 输出外层包裹的单个代码块，保留 Mermaid 等内嵌代码块
        if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.indexOf("```", 3) == trimmed.length() - 3) {
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
