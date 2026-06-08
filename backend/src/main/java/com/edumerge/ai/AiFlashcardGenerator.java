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
    @org.springframework.beans.factory.annotation.Qualifier("contentChatModel")
    private ChatModel chatLanguageModel;

    @Autowired
    private FlashcardMapper flashcardMapper;

    @Autowired
    private CardDeckService cardDeckService;

    /** 根据文档内容自动生成学习卡片 (每次生成创建一个 Deck) */
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
                matches = List.of(); // 范围取内容时无 EmbeddingMatch
            }
        } else {
            matches = retrieveTopChunks(docUuid, 10,
                    "核心知识点 关键概念 重要内容 定义 原理 方法 辨析 易错点 对比 应用场景 典型例题 条件边界 例外情况");
            if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }
            context = buildContext(matches);
        }
        log.info("提取上下文完成: docId={}, 块数={}", docId, matches.size());

        // 拼装已有卡片问题列表，告知 LLM 避免重复
        String existingHint = buildExistingHint(existingQuestions);
        String subjectRules = buildSubjectRules(getSubjectType(docId));

        String llmResponse = callLLM(context, existingHint, sectionContext, subjectRules);
        log.info("LLM 卡片生成响应: 长度={} 字符", llmResponse.length());

        // 创建卡片组, 将本次生成的卡片绑定到该组
        String deckTitle = extractDeckTitle(llmResponse);
        Long deckId = cardDeckService.create(docId, "FLASHCARD", deckTitle).getId();
        return parseAndSave(llmResponse, docId, userId, deckId, matches);
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

    private String callLLM(String context, String existingHint, String sectionContext, String subjectRules) {
        String template = """
                你是一个严谨的 AI 学习导师。请分析文档片段，提取5个核心知识点并转化为学习卡片。

                {COMMON_RULES}

                # 任务
                提取5个核心知识点，转化为问答式学习卡片。

                # 质量红线
                - 禁止元数据问题（章节归属、页码等）— 必须问概念本身
                - 禁止是/否型或答案仅为术语名的低价值问题
                - 禁止"X的定义是什么"、"X有哪些特点"这类仅考察记忆的浅层问题
                - 单卡不超过200字

                {SUBJECT_RULES}

                # 输出格式（仅输出JSON）
                {"deckTitle":"10字以内主题","cards":[{"question":"...","answer":"..."}]}

                {SECTION_HINT}
                # 文档上下文
                {CONTEXT}
                {EXISTING_HINT}
                """;
        String sectionHint = (sectionContext != null && !sectionContext.isBlank())
                ? "# 重点关注章节\n请重点围绕以下章节生成学习卡片：" + sectionContext.strip() + "\n"
                : "";
        SystemMessage system = new SystemMessage(template
                .replace("{COMMON_RULES}", buildCommonRules())
                .replace("{SUBJECT_RULES}", subjectRules)
                .replace("{CONTEXT}", context)
                .replace("{EXISTING_HINT}", existingHint)
                .replace("{SECTION_HINT}", sectionHint));

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上文档内容，生成5张学习卡片。"));
        ChatResponse response = chatContent(chatLanguageModel, messages);
        return response.aiMessage().text();
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
                String q = item.getOrDefault("question", ""), a = item.getOrDefault("answer", "");
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
