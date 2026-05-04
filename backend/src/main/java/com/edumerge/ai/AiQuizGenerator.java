package com.edumerge.ai;

import com.edumerge.entity.Quiz;
import com.edumerge.mapper.QuizMapper;
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

    /** 根据文档内容自动生成测试题 */
    public List<Quiz> generate(Long docId, Long userId, String docUuid) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 15,
                "核心概念 定义 原理 方法 应用场景 实践案例 技术细节 架构设计 关键要点 总结归纳");
        if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }

        String context = buildContext(matches);
        log.info("提取上下文完成: docId={}, 块数={}", docId, matches.size());

        String llmResponse = callLLM(context);
        log.info("LLM 测试题生成响应: 长度={} 字符", llmResponse.length());

        return parseAndSave(llmResponse, docId, userId, matches);
    }

    private String callLLM(String context) {
        SystemMessage system = new SystemMessage("""
                你是一位资深教育评测专家。请分析提供的文档片段，生成5道高质量单选题。

                # 严格规则 (必须遵守)
                1. **仅基于上下文**: 100%基于提供的文档片段内容出题，禁止编造文档中未提及的知识。
                2. **多维覆盖**: 题目应覆盖文档的不同章节/主题。
                3. **干扰项设计**: 每个题目的4个选项中需包含3个合理且有迷惑性的干扰项。

                # 难度分级
                - **基础概念** (2-3题): 基本定义、术语、原理的理解
                - **综合应用** (2-3题): 方法、流程、实践案例的分析判断

                # JSON格式约束
                [{"question":"...","options":["A. ...","B. ...","C. ...","D. ..."],
                  "correctAnswer":"A. ...","explanation":"...","difficulty":"basic"}]

                # 文档参考内容
                %s""".formatted(context));

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上文档内容，生成5道单选题，涵盖基础概念和综合应用两个维度。"));
        Response<AiMessage> response = chatLanguageModel.generate(messages);
        return response.content().text();
    }

    private List<Quiz> parseAndSave(String llmResponse, Long docId, Long userId,
                                     List<EmbeddingMatch<TextSegment>> matches) {
        List<Quiz> quizzes = new ArrayList<>();
        try {
            String json = extractJsonArray(llmResponse);
            List<Map<String, Object>> items = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> item = items.get(i);
                String q = (String) item.getOrDefault("question", "");
                if (q.isBlank()) continue;

                String optionsJson = objectMapper.writeValueAsString(item.getOrDefault("options", List.of()));
                String answer = (String) item.getOrDefault("correctAnswer", "");
                String explanation = (String) item.getOrDefault("explanation", "");
                String difficulty = (String) item.getOrDefault("difficulty", "basic");
                String src = i < matches.size() ? truncate(matches.get(i).embedded().text(), 1000) : "";

                quizzes.add(Quiz.builder().docId(docId).userId(userId).question(q)
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
}
