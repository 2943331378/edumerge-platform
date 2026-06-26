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

    /** 根据文档内容自动生成测试题 (并行分批生成, 总耗时减半) */
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

        // 并行分批: 2(选择)+1(填空) 和 1(选择)+1(填空) 同时生成
        final String ctx = context;
        final List<EmbeddingMatch<TextSegment>> m = matches;
        String llmResponse = parallelGenerate(
                () -> callLLM(ctx, existingHint, sectionContext, subjectRules, 2, 1),
                () -> callLLM(ctx, existingHint, sectionContext, subjectRules, 1, 1),
                (r1, r2) -> mergeQuizResponses(r1, r2)
        );
        log.info("LLM 测试题生成响应: 长度={} 字符", llmResponse.length());

        String deckTitle = extractDeckTitle(llmResponse);
        Long deckId = cardDeckService.create(docId, "QUIZ", deckTitle).getId();
        return parseAndSave(llmResponse, docId, userId, deckId, m);
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

    private String callLLM(String context, String existingHint, String sectionContext,
                            String subjectRules, int singleCount, int fillCount) {
        int totalCount = singleCount + fillCount;
        String systemTemplate = """
                你是一位严苛的标准化考试命题专家（如考研、CPA、AWS认证）。你的任务是基于文档内容，设计具备**高区分度、强迷惑性**的测试题。

                {COMMON_RULES}
                {SUBJECT_RULES}

                # 任务
                生成{TOTAL}道测试题：{SINGLE}道选择题（4选项）+ {FILL}道填空题（____标记，答案2-8字）。

                # 命题与干扰项构建策略
                1. **题干设计**：多使用"以下说法错误的是"、"在X条件下，最合适的方案是"、"导致Y现象的根本原因是"。
                2. **干扰项（Distractors）构建铁律**：
                   - **概念混淆**：使用与正确答案相似但本质不同的概念。
                   - **条件缺失**：选项本身在某种特定条件下是对的，但在题干给定的前提下是错的。
                   - **因果倒置**：将原因和结果颠倒。
                   - **常识陷阱**：利用日常直觉中正确但在专业领域错误的观点。
                   - *严禁使用"以上皆非"、"以上皆是"或明显荒谬的凑数选项。*

                # 难度分布
                - 基础题：考察核心概念的准确理解（非死记硬背，需理解内涵）
                - 应用题：必须是以下类型之一：
                  · 场景分析：给定一个具体情境，判断应如何应用
                  · 对比辨析：区分两个易混淆概念的适用条件
                  · 因果推理：改变某个前提条件，结果如何变化
                  · 错误诊断：给出一个常见错误用法，识别问题所在

                # explanation 字段格式要求
                explanation 必须为结构化纯文本，包含：
                - 【正确原因】：详细解释正确答案的底层逻辑和适用条件
                - 【干扰项分析】：逐项解释每个错误选项为什么错（B:... C:... D:...）

                # 质量红线
                - ❌ 严禁出现"是/否"判断题改造的单选题
                - ❌ 严禁选项之间存在明显的包含或互斥关系
                - ❌ 严禁在题干中泄露答案的暗示
                - ❌ 禁止元数据问题（章节归属、页码等）

                # 输出格式（仅输出JSON）
                {"deckTitle":"10字以内主题","quizzes":[
                  {"type":"SINGLE","question":"...","options":["A. ...","B. ...","C. ...","D. ..."],"correctAnswer":"A. ...","explanation":"【正确原因】...【干扰项分析】B:... C:... D:...","difficulty":"basic"},
                  {"type":"FILL_BLANK","question":"...____...","correctAnswer":"关键词","explanation":"【正确原因】...","difficulty":"application"}
                ]}
                选择题options必须有4项；填空题options必须为空数组[]。
                """;
        SystemMessage system = new SystemMessage(systemTemplate
                .replace("{TOTAL}", String.valueOf(totalCount))
                .replace("{SINGLE}", String.valueOf(singleCount))
                .replace("{FILL}", String.valueOf(fillCount))
                .replace("{COMMON_RULES}", buildCommonRules())
                .replace("{SUBJECT_RULES}", subjectRules));

        StringBuilder userSb = new StringBuilder();
        if (sectionContext != null && !sectionContext.isBlank()) {
            userSb.append("# 重点关注章节\n请重点围绕以下章节生成测试题：").append(sectionContext.strip()).append("\n\n");
        }
        userSb.append("# 文档上下文\n").append(context).append("\n");
        if (!existingHint.isEmpty()) {
            userSb.append(existingHint).append("\n");
        }
        userSb.append("请基于以上文档内容，生成").append(totalCount).append("道测试题");

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage(userSb.toString()));
        ChatResponse response = chatContent(chatLanguageModel, messages);
        return response.aiMessage().text();
    }

    /** 合并两批测验 JSON 响应为一个 */
    private String mergeQuizResponses(String response1, String response2) {
        List<Map<String, Object>> merged = new ArrayList<>();
        String deckTitle = "测试题";
        try {
            String json1 = extractJsonObject(response1);
            Map<String, Object> root1 = objectMapper.readValue(json1, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> quizzes1 = (List<Map<String, Object>>) (List<?>) root1.getOrDefault("quizzes", List.of());
            merged.addAll(quizzes1);
            Object t1 = root1.get("deckTitle");
            if (t1 instanceof String s && !s.isBlank()) deckTitle = s;
        } catch (Exception e) {
            log.warn("解析第一批测验失败: {}", e.getMessage());
        }
        try {
            String json2 = extractJsonObject(response2);
            Map<String, Object> root2 = objectMapper.readValue(json2, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> quizzes2 = (List<Map<String, Object>>) (List<?>) root2.getOrDefault("quizzes", List.of());
            merged.addAll(quizzes2);
            if (merged.size() <= quizzes2.size()) {
                Object t2 = root2.get("deckTitle");
                if (t2 instanceof String s && !s.isBlank()) deckTitle = s;
            }
        } catch (Exception e) {
            log.warn("解析第二批测验失败: {}", e.getMessage());
        }
        if (merged.isEmpty()) {
            log.error("两批测验均解析失败, 回退原始响应");
            return response1;
        }
        try {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("deckTitle", deckTitle);
            result.put("quizzes", merged);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("序列化合并结果失败: {}", e.getMessage());
            return response1;
        }
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
