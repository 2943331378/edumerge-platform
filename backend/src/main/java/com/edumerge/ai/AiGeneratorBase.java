package com.edumerge.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.Document;
import com.edumerge.entity.DocumentChunk;
import com.edumerge.mapper.DocumentChunkMapper;
import com.edumerge.mapper.DocumentMapper;
import com.edumerge.store.MilvusEmbeddingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 生成器基类 — 提供 Milvus 检索、上下文拼装、JSON 提取等公共工具方法
 */
@Slf4j
public abstract class AiGeneratorBase {

    @Autowired
    protected EmbeddingModel embeddingModel;

    @Autowired
    protected MilvusEmbeddingStore embeddingStore;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected DocumentMapper documentMapper;

    @Autowired
    protected DocumentChunkMapper documentChunkMapper;

    @Value("${app.rag.similarity-threshold:0.7}")
    protected double similarityThreshold;

    /** 从 Milvus 检索指定文档的核心语义块 (生成任务不用 minScore 过滤, 直接取 top-K) */
    protected List<EmbeddingMatch<TextSegment>> retrieveTopChunks(String docUuid, int topK, String semanticQuery) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(semanticQuery).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(0.0) // 生成任务不过滤, 确保能检索到文档内容
                    .filter(new IsEqualTo("document_id", docUuid))
                    .build();
            return embeddingStore.search(request).matches();
        } catch (Exception e) {
            log.error("Milvus 检索失败: docUuid={}, error={}", docUuid, e.getMessage(), e);
            return List.of();
        }
    }

    /** 按 chunk 范围从 MySQL 直接获取文档内容 (大纲章节选中时使用)，最多取 20 个 chunks */
    protected String buildContextFromRange(Long docId, int startChunk, int endChunk) {
        List<DocumentChunk> allChunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, docId)
                        .ge(DocumentChunk::getChunkIndex, startChunk)
                        .le(DocumentChunk::getChunkIndex, endChunk)
                        .orderByAsc(DocumentChunk::getChunkIndex));
        if (allChunks.isEmpty()) return "";

        // 大范围时采样: 取前8 + 中间4 + 后8，保证覆盖首尾且不超量
        List<DocumentChunk> chunks;
        if (allChunks.size() <= 20) {
            chunks = allChunks;
        } else {
            chunks = new ArrayList<>();
            chunks.addAll(allChunks.subList(0, 8)); // 前 8
            int mid = allChunks.size() / 2;
            chunks.addAll(allChunks.subList(mid - 2, mid + 2)); // 中间 4
            chunks.addAll(allChunks.subList(allChunks.size() - 8, allChunks.size())); // 后 8
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("【片段").append(i + 1).append("】\n");
            sb.append(chunks.get(i).getContent()).append("\n\n");
        }
        log.info("按 chunk 范围获取内容: docId={}, range=[{},{}], 总块数={}, 实际取={}", docId, startChunk, endChunk, allChunks.size(), chunks.size());
        return sb.toString();
    }

    /** 拼装检索上下文 */
    protected String buildContext(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            sb.append("【片段").append(i + 1).append("】\n");
            sb.append(matches.get(i).embedded().text()).append("\n\n");
        }
        return sb.toString();
    }

    /** 从 LLM 响应中提取 JSON 数组 */
    protected String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw;
    }

    /** 从 LLM 响应中提取 JSON 对象 (支持 markdown 代码块) */
    protected String extractJsonObject(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        // 去除 markdown 代码块
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return null;
    }

    /** 截断过长文本以适配数据库字段 */
    protected String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen - 3) + "..." : text;
    }

    /** 获取文档的学科类型，查不到时回退为 GENERAL */
    protected String getSubjectType(Long docId) {
        if (docId == null) return SubjectClassifier.GENERAL;
        try {
            Document doc = documentMapper.selectById(docId);
            String st = doc != null ? doc.getSubjectType() : null;
            return (st != null && !st.isBlank()) ? st : SubjectClassifier.GENERAL;
        } catch (Exception e) {
            return SubjectClassifier.GENERAL;
        }
    }

    /** 各 Generator 公共的基本规则，避免重复编写 */
    protected String buildCommonRules() {
        return """
                # 基本规则
                - 严格基于文档内容，严禁编造文档外信息
                - 使用简体中文输出；英文文档需翻译归纳，关键术语首次出现保留英文原词（如"自适应学习（adaptive learning）"）

                # 深度要求
                - 面向考试和深度理解，不要停留在"是什么"层面，要覆盖"为什么"和"怎么用"
                - 优先考察：原理推导、条件边界、易混淆辨析、典型应用场景、对比分析、异常/特殊情况
                - 避免生成仅靠记忆术语就能回答的浅层问题
                """;
    }

    /**
     * 根据学科类型生成针对性出题策略指令
     */
    protected String buildSubjectRules(String subjectType) {
        if (subjectType == null) subjectType = SubjectClassifier.GENERAL;
        return switch (subjectType) {
            case "ALGORITHM" -> """
                    # 学科策略：算法设计与分析
                    - 代码走读：给出代码片段，问时间复杂度、输出结果或执行过程
                    - 手动模拟：给定输入实例，要求逐步追踪算法执行（如画出递归调用栈、回溯树）
                    - 算法对比：两种算法在同一问题上的效率/适用性/时间复杂度比较
                    - 策略选择：给一个问题，问应该用哪种算法策略（贪心/DP/回溯/分治），并说明理由
                    - 边界分析：什么输入下效率最差？什么条件下算法失效？
                    """;
            case "MATH" -> """
                    # 学科策略：数学
                    - 公式推导：要求推导或证明某个公式/定理
                    - 计算应用：给出具体数值，要求计算求解
                    - 概念辨析：易混淆概念的严格定义区别（如连续 vs 可导、概率 vs 似然）
                    - 几何/代数直觉：公式背后的几何意义或直观理解
                    - 条件分析：定理的适用前提和不满足时的反例
                    """;
            case "PROGRAMMING" -> """
                    # 学科策略：程序设计
                    - 代码输出：给出代码片段，问运行结果
                    - 语法辨析：相似语法/API 的区别（如 == vs equals、let vs const vs var）
                    - 调试分析：给出有 bug 的代码，要求找出错误
                    - 设计选择：在特定场景下选择合适的设计模式或数据结构
                    - 概念应用：面向对象/函数式编程概念在实际代码中的体现
                    """;
            case "SCIENCE" -> """
                    # 学科策略：自然科学
                    - 原理应用：用物理/化学/生物原理解释现象
                    - 公式计算：给定条件进行定量计算
                    - 实验分析：实验步骤、变量控制、结果推断
                    - 因果推理：改变某个条件，结果如何变化
                    - 概念辨析：相似概念的区别（如速度 vs 加速度、基因型 vs 表现型）
                    """;
            case "THEORY" -> """
                    # 学科策略：计算机理论
                    - 原理辨析：相似机制的区别（如进程 vs 线程、TCP vs UDP、B+树 vs B树）
                    - 流程分析：某个过程的步骤和状态变化（如三次握手、页面置换、编译过程）
                    - 对比分析：不同方案的优劣对比（如不同调度算法、不同索引结构）
                    - 场景选择：给定需求，选择合适的技术方案并说明理由
                    - 计算题：给定参数计算结果（如寻道时间、吞吐量、缓存命中率）
                    """;
            case "MEDICAL" -> """
                    # 学科策略：医学
                    - 机制分析：疾病发病机制、药物作用机制的因果链
                    - 鉴别诊断：相似症状/疾病的鉴别要点
                    - 临床推理：给定症状和检查结果，推断诊断或治疗方案
                    - 药理对比：同类药物的作用机制、适应症、不良反应比较
                    - 病理生理：正常与病理状态的对比分析
                    """;
            case "HUMANITIES" -> """
                    # 学科策略：人文社科
                    - 案例分析：给定情境，要求运用理论分析问题
                    - 理论对比：不同理论/学派的核心观点区别
                    - 法条/政策应用：给定案例，判断适用的法条或政策
                    - 因果推理：某个决策/事件会产生什么影响
                    - 批判思考：评估某个观点的合理性，给出正反论据
                    """;
            default -> """
                    # 学科策略：通用
                    - 注重理解而非记忆，考察"为什么"和"怎么用"
                    - 优先出对比辨析、场景应用、因果推理类题目
                    """;
        };
    }

    /** AI 模型调用熔断器：连续 5 次失败后开启，30 秒冷却。所有 AI 调用共享此实例。 */
    public static final CircuitBreaker AI_CIRCUIT_BREAKER = new CircuitBreaker("AI-Model", 5, 30_000);

    /** 调用内容生成模型 (带熔断保护) */
    protected ChatResponse chatContent(ChatModel model, List<ChatMessage> messages) {
        return AI_CIRCUIT_BREAKER.execute(() -> model.chat(messages));
    }
}
