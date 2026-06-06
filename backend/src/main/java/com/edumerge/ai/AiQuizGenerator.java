package com.edumerge.ai;

import com.edumerge.entity.Quiz;
import com.edumerge.mapper.QuizMapper;
import com.edumerge.service.CardDeckService;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
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
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private QuizMapper quizMapper;

    @Autowired
    private CardDeckService cardDeckService;

    /** 根据文档内容自动生成测试题 (每次生成创建一个 Deck) */
    public List<Quiz> generate(Long docId, Long userId, String docUuid, List<String> existingQuestions, String sectionContext) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 15,
                "核心概念 定义 原理 方法 应用场景 实践案例 技术细节 架构设计 关键要点 总结归纳 key concepts definition principles methods use cases examples technical details architecture key points summary");
        if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }

        String context = buildContext(matches);
        log.info("提取上下文完成: docId={}, 块数={}", docId, matches.size());

        String existingHint = buildExistingHint(existingQuestions);

        String llmResponse = callLLM(context, existingHint, sectionContext);
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

    private String callLLM(String context, String existingHint, String sectionContext) {
        String template = """
                你是一个严谨的 AI 学习导师。请分析文档片段，生成5道高质量测试题。

                {COMMON_RULES}

                # 任务
                生成5道测试题：3道选择题（4选项，含3个有迷惑性的干扰项）+ 2道填空题（____标记，答案2-8字）。

                # 质量红线
                - 禁止元数据问题（章节归属、页码等）、禁止常识题
                - 选择题干扰项须有迷惑性，来自文档语义
                - 覆盖文档不同主题/概念层级
                - 难度分布: 2-3题basic（定义/术语）+ 2-3题application（分析/应用）

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
                .replace("{CONTEXT}", context)
                .replace("{EXISTING_HINT}", existingHint)
                .replace("{SECTION_HINT}", sectionHint));

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上文档内容，生成5道测试题（3道选择题 + 2道填空题），涵盖基础概念和综合应用两个维度。"));
        Response<AiMessage> response = chatLanguageModel.generate(messages);
        return response.content().text();
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
