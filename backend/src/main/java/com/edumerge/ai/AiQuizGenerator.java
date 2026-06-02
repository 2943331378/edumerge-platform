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
    public List<Quiz> generate(Long docId, Long userId, String docUuid, List<String> existingQuestions) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 15,
                "核心概念 定义 原理 方法 应用场景 实践案例 技术细节 架构设计 关键要点 总结归纳 key concepts definition principles methods use cases examples technical details architecture key points summary");
        if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }

        String context = buildContext(matches);
        log.info("提取上下文完成: docId={}, 块数={}", docId, matches.size());

        String existingHint = buildExistingHint(existingQuestions);

        String llmResponse = callLLM(context, existingHint);
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

    private String callLLM(String context, String existingHint) {
        String template = """
                你是一个严谨的 AI 学习导师。请分析提供的文档片段，生成5道高质量单选题。

                # 绝对禁止 (违反将导致严重质量问题)
                1. **禁止元数据问题**: 严禁测试文档结构、章节归属、页码位置、模块划分等。
                   反面示例(禁止):
                   - "以下哪个选项属于文档的第3章？"
                   - "关于XXX的规约位于文档的哪一部分？"
                   - "文档中第几条规则描述了YYY？"
                2. **禁止废话问题**: 严禁答案显而易见、或仅凭常识无需阅读文档即可回答的问题。
                   反面示例(禁止):
                   - "代码规范重要吗？" (答案显然是"重要")
                   - "以下哪项是Java的关键字？" (常识, 无需文档)

                # 优先级要求
                1. **文档为事实依据**: 必须以提供的文档上下文为唯一出题来源, 严禁编造。
                2. **多维覆盖**: 题目应覆盖文档的不同章节/主题/概念层级。
                3. **干扰项设计**: 每个题目的4个选项中需包含3个合理且有迷惑性的干扰项, 干扰项也应来自文档语义。
                4. **中文输出**: question、options、correctAnswer 和 explanation 必须使用简体中文；如果文档是英文，请基于英文原文翻译、归纳和解释。
                5. **术语保留**: 英文关键术语首次出现时保留英文原词，例如"学习分析（learning analytics）"。
                6. **难度分级**:
                   - **basic** (2-3题): 基本定义、术语、原理的准确理解
                   - **application** (2-3题): 方法选择、流程判断、实践案例的分析应用

                # JSON格式约束 (严格)
                {"deckTitle": "根据文档内容生成的简短标题(10字以内)",
                 "quizzes": [
                   {"question":"...","options":["A. ...","B. ...","C. ...","D. ..."],
                    "correctAnswer":"A. ...","explanation":"...","difficulty":"basic"}
                 ]}

                deckTitle 要求: 提炼文档核心主题, 如"Java并发编程测试"、"机器学习基础测验"、"分布式系统设计题"。

                # 文档上下文
                {CONTEXT}
                {EXISTING_HINT}
                """;
        SystemMessage system = new SystemMessage(template
                .replace("{CONTEXT}", context)
                .replace("{EXISTING_HINT}", existingHint));

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上文档内容，生成5道单选题，涵盖基础概念和综合应用两个维度。"));
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

                String optionsJson = objectMapper.writeValueAsString(item.getOrDefault("options", List.of()));
                String answer = (String) item.getOrDefault("correctAnswer", "");
                String explanation = (String) item.getOrDefault("explanation", "");
                String difficulty = (String) item.getOrDefault("difficulty", "basic");
                String src = i < matches.size() ? truncate(matches.get(i).embedded().text(), 1000) : "";

                quizzes.add(Quiz.builder().docId(docId).deckId(deckId).userId(userId).question(q)
                        .options(optionsJson).answer(answer).explanation(explanation)
                        .sourceSegment(src).quizType("SINGLE")
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
