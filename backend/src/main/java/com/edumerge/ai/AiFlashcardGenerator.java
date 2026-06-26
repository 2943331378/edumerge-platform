package com.edumerge.ai;

import com.edumerge.entity.Flashcard;
import com.edumerge.mapper.FlashcardMapper;
import com.edumerge.service.CardDeckService;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Map;

/**
 * AI 学习卡片生成器 (架构红线: LangChain4j 隔离在 ai 包)
 */
@Slf4j
@Service
public class AiFlashcardGenerator extends AiGeneratorBase {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("flashcardChatModel")
    private ChatModel chatLanguageModel;

    @Autowired
    private FlashcardMapper flashcardMapper;

    @Autowired
    private CardDeckService cardDeckService;

    /** 根据文档内容自动生成学习卡片 (并行分批生成, 总耗时减半) */
    public List<Flashcard> generate(Long docId, Long userId, String docUuid, List<String> existingQuestions, String sectionContext, Integer startChunk, Integer endChunk) {
        // 优先按大纲 chunk 范围直接取内容，否则语义搜索
        String context;
        List<EmbeddingMatch<TextSegment>> matches;
        if (startChunk != null && endChunk != null) {
            context = buildContextFromRange(docId, startChunk, endChunk);
            if (context.isEmpty()) {
                log.warn("按 chunk 范围未获取到内容, 回退语义搜索: docId={}, range=[{},{}]", docId, startChunk, endChunk);
                matches = retrieveTopChunks(docUuid, 10,
                        "核心知识点 关键概念 重要内容 定义 原理 方法 辨析 易错点 对比 应用场景 典型例题 条件边界 例外情况");
                if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }
                context = buildContext(matches);
            } else {
                matches = List.of();
            }
        } else {
            matches = retrieveTopChunks(docUuid, 10,
                    "核心知识点 关键概念 重要内容 定义 原理 方法 辨析 易错点 对比 应用场景 典型例题 条件边界 例外情况");
            if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }
            context = buildContext(matches);
        }
        log.info("提取上下文完成: docId={}, 块数={}", docId, matches.size());

        String existingHint = buildExistingHint(existingQuestions);
        String subjectRules = buildSubjectRules(getSubjectType(docId));

        // 并行分批: 3+2 张卡片同时生成
        final String ctx = context;
        final List<EmbeddingMatch<TextSegment>> m = matches;
        String llmResponse = parallelGenerate(
                () -> callLLM(ctx, existingHint, sectionContext, subjectRules, 3),
                () -> callLLM(ctx, existingHint, sectionContext, subjectRules, 2),
                (r1, r2) -> mergeCardResponses(r1, r2)
        );
        log.info("LLM 卡片生成响应: 长度={} 字符", llmResponse.length());

        String deckTitle = extractDeckTitle(llmResponse);
        Long deckId = cardDeckService.create(docId, "FLASHCARD", deckTitle).getId();
        return parseAndSave(llmResponse, docId, userId, deckId, m);
    }

    private String buildExistingHint(List<String> existingQuestions) {
        if (existingQuestions == null || existingQuestions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n# 已有卡片(严禁生成相同或高度相似的问题)\n");
        for (int i = 0; i < Math.min(existingQuestions.size(), 30); i++) {
            sb.append("- ").append(existingQuestions.get(i)).append("\n");
        }
        sb.append("\n请确保新生成的问题与以上已有问题在语义上有明显区分。\n");
        return sb.toString();
    }

    private String callLLM(String context, String existingHint, String sectionContext, String subjectRules, int cardCount) {
        String systemTemplate = """
                你是一位认知心理学专家和记忆大师。你的任务是基于文档内容，生成一组**高信息密度、促进深度理解**的 Anki 风格闪卡。

                {COMMON_RULES}
                {SUBJECT_RULES}

                # 闪卡设计哲学（拒绝死记硬背）
                1. **情境触发**：正面(question)不要只问定义，要提供情境、条件、症状或代码片段，让大脑在"解决问题"中回忆。
                2. **原子化**：一张卡片只考察一个核心知识点（Minimum Information Principle）。
                3. **记忆锚点**：背面(answer)必须提供辅助记忆的"钩子"（如：类比、口诀、词根、图像描述）。

                # 卡片类型矩阵（动态选择最合适的类型）
                - **概念辨析卡**：正面给出易混淆场景，背面给出区分核心差异。
                - **机制推导卡**：正面给出初始状态和条件，背面给出推导过程和最终结果。
                - **填空/代码卡**：正面给出缺失关键部分的代码或公式，背面补全并解释原因。
                - **反向应用卡**：正面给出结果或现象，背面反推原因或底层机制。

                # 任务
                提取{COUNT}个核心知识点，转化为高质量学习卡片。

                # 质量红线（触发即废弃该卡片）
                - ❌ 禁止"X的定义是什么？"
                - ❌ 禁止"X有哪些特点？"（列举类问题如果超过3点，必须拆分为多张卡片或改为填空）
                - ❌ 禁止答案超过 100 字（必须精简提炼）
                - ❌ 禁止元数据问题（章节归属、页码等）

                # 输出格式（仅输出JSON，question=正面，answer=背面）
                {"deckTitle":"10字以内主题","cards":[{"question":"【情境/问题/代码片段】","answer":"【核心答案+记忆锚点】"}]}
                """;
        SystemMessage system = new SystemMessage(systemTemplate
                .replace("{COUNT}", String.valueOf(cardCount))
                .replace("{COMMON_RULES}", buildCommonRules())
                .replace("{SUBJECT_RULES}", subjectRules));

        StringBuilder userSb = new StringBuilder();
        if (sectionContext != null && !sectionContext.isBlank()) {
            userSb.append("# 重点关注章节\n请重点围绕以下章节生成学习卡片：").append(sectionContext.strip()).append("\n\n");
        }
        userSb.append("# 文档上下文\n").append(context).append("\n");
        if (!existingHint.isEmpty()) {
            userSb.append(existingHint).append("\n");
        }
        userSb.append("请基于以上文档内容，生成").append(cardCount).append("张学习卡片。");

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage(userSb.toString()));
        ChatResponse response = chatContent(chatLanguageModel, messages);
        return response.aiMessage().text();
    }

    /** 合并两批卡片 JSON 响应为一个 */
    private String mergeCardResponses(String response1, String response2) {
        List<Map<String, String>> merged = new ArrayList<>();
        String deckTitle = "学习卡片";
        try {
            String json1 = extractJsonObject(response1);
            Map<String, Object> root1 = objectMapper.readValue(json1, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, String>> cards1 = (List<Map<String, String>>) (List<?>) root1.getOrDefault("cards", List.of());
            merged.addAll(cards1);
            Object t1 = root1.get("deckTitle");
            if (t1 instanceof String s && !s.isBlank()) deckTitle = s;
        } catch (Exception e) {
            log.warn("解析第一批卡片失败: {}", e.getMessage());
        }
        try {
            String json2 = extractJsonObject(response2);
            Map<String, Object> root2 = objectMapper.readValue(json2, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, String>> cards2 = (List<Map<String, String>>) (List<?>) root2.getOrDefault("cards", List.of());
            merged.addAll(cards2);
            if (merged.size() <= cards2.size()) {
                Object t2 = root2.get("deckTitle");
                if (t2 instanceof String s && !s.isBlank()) deckTitle = s;
            }
        } catch (Exception e) {
            log.warn("解析第二批卡片失败: {}", e.getMessage());
        }
        if (merged.isEmpty()) {
            log.error("两批卡片均解析失败, 回退原始响应");
            return response1;
        }
        try {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("deckTitle", deckTitle);
            result.put("cards", merged);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("序列化合并结果失败: {}", e.getMessage());
            return response1;
        }
    }

    private List<Flashcard> parseAndSave(String llmResponse, Long docId, Long userId,
                                          Long deckId, List<EmbeddingMatch<TextSegment>> matches) {
        List<Flashcard> cards = new ArrayList<>();
        try {
            String json = extractJsonObject(llmResponse);
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) (List<?>) (root.getOrDefault("cards", List.of()));
            for (int i = 0; i < items.size(); i++) {
                Map<String, String> item = items.get(i);
                // 兼容 question/answer 和 front/back 两种 key
                String q = item.getOrDefault("question", item.getOrDefault("front", ""));
                String a = item.getOrDefault("answer", item.getOrDefault("back", ""));
                if (q.isBlank() || a.isBlank()) continue;
                String src = i < matches.size() ? truncate(matches.get(i).embedded().text(), 1000) : "";
                cards.add(Flashcard.builder().docId(docId).deckId(deckId).userId(userId)
                        .question(q).answer(a).sourceSegment(src).status("ACTIVE").build());
            }
            if (!cards.isEmpty()) {
                flashcardMapper.insert(cards, 50); // 批量写入
                log.info("学习卡片写入完成: docId={}, 卡片数={}", docId, cards.size());
            }
        } catch (Exception e) {
            log.error("解析 LLM 卡片 JSON 失败: error={}, raw={}", e.getMessage(), llmResponse, e);
        }
        return cards;
    }

    /** 从 LLM 响应中提取 deckTitle */
    private String extractDeckTitle(String llmResponse) {
        try {
            String json = extractJsonObject(llmResponse);
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object title = root.get("deckTitle");
            if (title instanceof String s && !s.isBlank()) return s.trim();
        } catch (Exception ignored) {}
        return null;
    }
}
