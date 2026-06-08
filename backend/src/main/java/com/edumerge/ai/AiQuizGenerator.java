package com.edumerge.ai;

import com.edumerge.entity.Quiz;
import com.edumerge.mapper.QuizMapper;
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
 * AI 测试题生成器 (架构红线: LangChain4j 隔离在 ai 包)
 */
@Slf4j
@Service
public class AiQuizGenerator extends AiGeneratorBase {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("contentChatModel")
    private ChatModel chatLanguageModel;

    @Autowired
    private QuizMapper quizMapper;

    @Autowired
    private CardDeckService cardDeckService;

    /** 根据文档内容自动生成测试题 (每次生成创建一个 Deck) */
    public List<Quiz> generate(Long docId, Long userId, String docUuid, List<String> existingQuestions, String sectionContext, Integer startChunk, Integer endChunk) {
        String context;
        List<EmbeddingMatch<TextSegment>> matches;
        if (startChunk != null && endChunk != null) {
            context = buildContextFromRange(docId, startChunk, endChunk);
            if (context.isEmpty()) {
                log.warn("按 chunk 范围未获取到内容, 回退语义搜索: docId={}, range=[{},{}]", docId, startChunk, endChunk);
                matches = retrieveTopChunks(docUuid, 15,
                        "核心概念 定义 原理 方法 应用场景 实践案例 技术细节 关键要点 辨析 易错点 对比 条件边界 典型例题");
                if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }
                context = buildContext(matches);
            } else {
                matches = List.of();
            }
        } else {
            matches = retrieveTopChunks(docUuid, 15,
                    "核心概念 定义 原理 方法 应用场景 实践案例 技术细节 关键要点 辨析 易错点 对比 条件边界 典型例题");
            if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }
            context = buildContext(matches);
        }
        log.info("提取上下文完成: docId={}, 块数={}", docId, matches.size());

        String existingHint = buildExistingHint(existingQuestions);
        String subjectRules = buildSubjectRules(getSubjectType(docId));

        String llmResponse = callLLM(context, existingHint, sectionContext, subjectRules);
        log.info("LLM 测试题生成响应: 长度={} 字符", llmResponse.length());

        String deckTitle = extractDeckTitle(llmResponse);
        Long deckId = cardDeckService.create(docId, "QUIZ", deckTitle).getId();
        return parseAndSave(llmResponse, docId, userId, deckId, matches);
    }

    private String buildExistingHint(List<String> existingQuestions) {
        if (existingQuestions == null || existingQuestions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n# 已有题目(严禁生成相同或高度相似的问题)\n");
        for (int i = 0; i < Math.min(existingQuestions.size(), 30); i++) {
            sb.append("- ").append(existingQuestions.get(i)).append("\n");
        }
        sb.append("\n请确保新生成的题目与以上已有题目在考察角度、知识维度上有明显区分。\n");
        return sb.toString();
    }

    private String callLLM(String context, String existingHint, String sectionContext, String subjectRules) {
        String template = """
                你是一个严谨的 AI 学习导师。请分析文档片段，生成5道高质量测试题。

                {COMMON_RULES}

                # 任务
                生成5道测试题：3道选择题（4选项，含3个有迷惑性的干扰项）+ 2道填空题（____标记，答案2-8字）。

                # 质量红线
                - 禁止元数据问题（章节归属、页码等）、禁止常识题
                - 禁止"以下哪项是X的定义"这类仅考察记忆的低价值题
                - 选择题干扰项须有迷惑性，来自文档语义，且每个干扰项都应有合理依据
                - 覆盖文档不同主题/概念层级

                # 难度分布
                - 2题basic：考察核心概念的准确理解（非死记硬背，需理解内涵）
                - 3题application：必须是以下类型之一：
                  · 场景分析：给定一个具体情境，判断应如何应用
                  · 对比辨析：区分两个易混淆概念的适用条件
                  · 因果推理：改变某个前提条件，结果如何变化
                  · 错误诊断：给出一个常见错误用法，识别问题所在

                {SUBJECT_RULES}

                # 输出格式（仅输出JSON）
                {"deckTitle":"10字以内主题","quizzes":[
                  {"type":"SINGLE","question":"...","options":["A. ...","B. ...","C. ...","D. ..."],"correctAnswer":"A. ...","explanation":"...","difficulty":"basic"},
                  {"type":"FILL_BLANK","question":"...____...","correctAnswer":"关键词","explanation":"...","difficulty":"application"}
                ]}
                选择题options必须有4项；填空题options必须为空数组[]。

                {SECTION_HINT}
                # 文档上下文
                {CONTEXT}
                {EXISTING_HINT}
                """;
        String sectionHint = (sectionContext != null && !sectionContext.isBlank())
                ? "# 重点关注章节\n请重点围绕以下章节生成测试题：" + sectionContext.strip() + "\n"
                : "";
        SystemMessage system = new SystemMessage(template
                .replace("{COMMON_RULES}", buildCommonRules())
                .replace("{SUBJECT_RULES}", subjectRules)
                .replace("{CONTEXT}", context)
                .replace("{EXISTING_HINT}", existingHint)
                .replace("{SECTION_HINT}", sectionHint));

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上文档内容，生成5道测试题（3道选择题 + 2道填空题），涵盖基础概念和综合应用两个维度。"));
        ChatResponse response = chatContent(chatLanguageModel, messages);
        return response.aiMessage().text();
    }

    private List<Quiz> parseAndSave(String llmResponse, Long docId, Long userId,
                                     Long deckId, List<EmbeddingMatch<TextSegment>> matches) {
        List<Quiz> quizzes = new ArrayList<>();
        try {
            String json = extractJsonObject(llmResponse);
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) (List<?>) (root.getOrDefault("quizzes", List.of()));
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> item = items.get(i);
                String q = (String) item.getOrDefault("question", "");
                if (q.isBlank()) continue;

                String type = (String) item.getOrDefault("type", "SINGLE");
                boolean isFillBlank = "FILL_BLANK".equalsIgnoreCase(type);

                // 填空题 options 为空数组，选择题正常解析
                Object rawOptions = item.getOrDefault("options", List.of());
                @SuppressWarnings("unchecked")
                List<String> optionList = rawOptions instanceof List ? (List<String>) rawOptions : List.of();
                String optionsJson = objectMapper.writeValueAsString(optionList);

                String answer = (String) item.getOrDefault("correctAnswer", "");
                String explanation = (String) item.getOrDefault("explanation", "");
                String difficulty = (String) item.getOrDefault("difficulty", "basic");
                String src = i < matches.size() ? truncate(matches.get(i).embedded().text(), 1000) : "";

                quizzes.add(Quiz.builder().docId(docId).deckId(deckId).userId(userId).question(q)
                        .options(optionsJson).answer(answer).explanation(explanation)
                        .sourceSegment(src).quizType(isFillBlank ? "FILL_BLANK" : "SINGLE")
                        .difficulty("application".equals(difficulty) ? 4 : 2).status("ACTIVE").build());
            }
            if (!quizzes.isEmpty()) {
                quizMapper.insert(quizzes, 50);
                log.info("测试题写入完成: docId={}, 题目数={}", docId, quizzes.size());
            }
        } catch (Exception e) {
            log.error("解析 LLM 测试题 JSON 失败: error={}, raw={}", e.getMessage(), llmResponse, e);
        }
        return quizzes;
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
