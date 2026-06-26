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
        String subjectType = getSubjectType(docId);
        log.info("思维导图上下文构建完成: docId={}, 块数={}, 耗时={}ms", docId, chunkCount, System.currentTimeMillis() - startTime);

        long llmStart = System.currentTimeMillis();
        String markdown = callLLM(context, sectionContext, subjectType);
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
        String subjectType = getSubjectType(docId);
        log.info("流式思维导图上下文构建完成: docId={}, 耗时={}ms", docId, System.currentTimeMillis() - startTime);

        List<ChatMessage> messages = buildMindMapMessages(context, sectionContext, subjectType);

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

    private String callLLM(String context, String sectionContext, String subjectType) {
        List<ChatMessage> messages = buildMindMapMessages(context, sectionContext, subjectType);
        ChatResponse response = chatContent(chatLanguageModel, messages);
        return response.aiMessage().text();
    }

    private List<ChatMessage> buildMindMapMessages(String context, String sectionContext, String subjectType) {
        String subjectGuide = buildMindMapSubjectGuide(subjectType);
        String systemTemplate = """
                你是一位擅长知识可视化和结构化思维的专家。你的任务是基于文档内容，提取核心知识骨架，生成一份**层级清晰、突出关联**的 Markdown 思维导图。

                {COMMON_RULES}
                {SUBJECT_GUIDE}

                # 思维导图设计原则
                1. **广度优先于深度**：严格控制层级，最多不超过 4 级（中心主题 → 核心分支 → 子分支 → 叶子节点）。避免变成冗长的文本列表。
                2. **关键词化**：节点内容必须是精炼的"关键词"或"短句"（不超过15个字），严禁大段文本。
                3. **凸显枢纽**：识别出连接多个分支的"核心枢纽概念"，并在中心主题后单独列出。

                # 输出格式（仅输出 Markdown）
                # [文档核心主题]

                ## 核心枢纽概念
                *(提取 2-3 个贯穿全文、连接多个分支的核心概念，并用一句话解释其枢纽作用)*
                - **[枢纽概念1]**：...
                - **[枢纽概念2]**：...

                ## 知识结构树
                *(使用 Markdown 标题层级，严格控制在 4 级以内)*
                # [一级分支：宏观模块/核心阶段]
                ## [二级分支：子模块/关键机制]
                ### [三级分支：具体概念/步骤]
                - [四级分支：关键细节/参数/条件]

                ## 跨分支关联 (Hidden Links)
                *(指出知识树中不同分支之间隐藏的因果、对比或依赖关系)*
                1. [分支A的节点X] → 导致/影响 → [分支B的节点Y]：(简述关联机制)
                2. [分支C的节点Z] ↔ 对比/互斥 → [分支D的节点W]：(简述核心差异)

                # 格式约束
                1. 仅输出 Markdown，禁止引导语/结束语
                2. 每个节点须为精炼短语（不超过15字），严禁大段文本
                3. 层级间无空行，保持紧凑树状结构
                4. 如果某一部分内容属于线性流程，请在节点后追加 (流程: 1→2→3) 标记

                # Mermaid 语法铁律（若需内嵌 Mermaid 图表）
                1. 节点文本必须用双引号包裹：A["说明文字"]，禁止裸露特殊字符
                2. 边标签禁止使用引号：A -->|标签文字| B
                3. subgraph 标题禁止括号：subgraph 标题名称
                4. 节点 ID 只用英文字母和数字：A、B、node1，禁止中文 ID
                """;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemTemplate
                .replace("{COMMON_RULES}", buildCommonRules())
                .replace("{SUBJECT_GUIDE}", subjectGuide)));

        StringBuilder userSb = new StringBuilder();
        if (sectionContext != null && !sectionContext.isBlank()) {
            userSb.append("# 重点关注章节\n请重点围绕以下章节生成思维导图，但保持整体结构完整:\n").append(sectionContext.strip()).append("\n\n");
        }
        userSb.append("# 文档上下文\n").append(context).append("\n");
        userSb.append("请基于以上文档内容，生成一份结构清晰的 Markdown 思维导图。仅输出 Markdown 内容。");
        messages.add(new UserMessage(userSb.toString()));
        return messages;
    }

    /**
     * 根据学科类型生成思维导图的结构化指引 — 引导 LLM 按学科特点组织节点层级
     * (区别于 buildSubjectRules 的出题策略，本方法聚焦知识结构)
     */
    private String buildMindMapSubjectGuide(String subjectType) {
        if (subjectType == null) subjectType = SubjectClassifier.GENERAL;
        return switch (subjectType) {
            case "ALGORITHM" -> """
                    # 学科结构指引：算法与数据结构
                    每个数据结构/算法概念的分支应按以下维度展开，而非仅罗列名称：
                    - **定义与结构**：是什么、由哪些部分组成、逻辑结构和物理结构
                    - **核心操作**：增删查改的时间复杂度，关键操作的执行步骤
                    - **典型实现**：关键代码片段或伪代码的思路（如链表插入、二叉树遍历顺序）
                    - **时间/空间复杂度**：最好、平均、最坏情况的分析
                    - **典型应用**：在哪些场景下使用，解决什么问题
                    - **对比与选型**：与同类数据结构/算法的异同（如数组 vs 链表、快排 vs 归并）

                    树形结构（二叉树、B树、堆等）的分支应体现层次遍历/遍历顺序；
                    图相关概念应区分有向/无向、带权/无权、存储方式（邻接矩阵 vs 邻接表）；
                    排序/搜索算法应体现稳定性、是否原地、适用场景等对比维度。
                    """;
            case "MATH" -> """
                    # 学科结构指引：数学
                    每个定理/概念的分支应体现：
                    - 定义与符号 → 直觉理解（几何/物理意义） → 关键公式 → 适用条件与边界 → 典型应用题型
                    注意公式间的推导关系和定理间的逻辑依赖。
                    """;
            case "PROGRAMMING" -> """
                    # 学科结构指引：程序设计
                    每个概念的分支应体现：
                    - 语法/API 定义 → 语义规则 → 典型用法示例 → 常见陷阱 → 与相似概念的对比
                    注意代码范式（OOP/FP）和设计模式的层次关系。
                    """;
            case "THEORY" -> """
                    # 学科结构指引：计算机理论
                    每个机制/协议的分支应体现：
                    - 工作原理 → 状态/流程 → 关键参数 → 性能指标 → 与其他方案的对比
                    注意协议栈的层次关系和系统组件的依赖关系。
                    """;
            default -> "";
        };
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
