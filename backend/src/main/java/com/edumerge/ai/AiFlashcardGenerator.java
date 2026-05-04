package com.edumerge.ai;

import com.edumerge.entity.Flashcard;
import com.edumerge.mapper.FlashcardMapper;
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
 * AI 学习卡片生成器 (架构红线: LangChain4j 隔离在 ai 包)
 */
@Slf4j
@Service
public class AiFlashcardGenerator extends AiGeneratorBase {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private FlashcardMapper flashcardMapper;

    /** 根据文档内容自动生成学习卡片 */
    public List<Flashcard> generate(Long docId, Long userId, String docUuid) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 10,
                "核心知识点 关键概念 重要内容 定义 原理 方法 总结");
        if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }

        String context = buildContext(matches);
        log.info("提取上下文完成: docId={}, 块数={}", docId, matches.size());

        String llmResponse = callLLM(context);
        log.info("LLM 卡片生成响应: 长度={} 字符", llmResponse.length());

        return parseAndSave(llmResponse, docId, userId, matches);
    }

    private String callLLM(String context) {
        SystemMessage system = new SystemMessage("""
                你是一位资深教育专家。请分析提供的文档片段，提取5个核心知识点并转化为学习卡片。

                # 严格规则 (必须遵守)
                1. **仅基于上下文**: 100%基于提供的文档片段内容，禁止编造文档中未提及的知识。
                2. **精准提取**: 选择文档中最核心、最具有学习价值的知识点。
                3. **简洁表达**: question应清晰明确，answer应准确简洁，单条卡片不超过200字。

                # 输出格式
                [{"question": "知识点问题1", "answer": "对应答案1"}]

                # 文档参考内容
                %s""".formatted(context));

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上文档内容，生成5张学习卡片。"));
        Response<AiMessage> response = chatLanguageModel.generate(messages);
        return response.content().text();
    }

    private List<Flashcard> parseAndSave(String llmResponse, Long docId, Long userId,
                                          List<EmbeddingMatch<TextSegment>> matches) {
        List<Flashcard> cards = new ArrayList<>();
        try {
            String json = extractJsonArray(llmResponse);
            List<Map<String, String>> items = objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
            for (int i = 0; i < items.size(); i++) {
                Map<String, String> item = items.get(i);
                String q = item.getOrDefault("question", ""), a = item.getOrDefault("answer", "");
                if (q.isBlank() || a.isBlank()) continue;
                String src = i < matches.size() ? truncate(matches.get(i).embedded().text(), 1000) : "";
                cards.add(Flashcard.builder().docId(docId).userId(userId).question(q).answer(a)
                        .sourceSegment(src).status("ACTIVE").build());
            }
            if (!cards.isEmpty()) {
                flashcardMapper.insert(cards, cards.size()); // 批量写入
                log.info("学习卡片写入完成: docId={}, 卡片数={}", docId, cards.size());
            }
        } catch (Exception e) {
            log.error("解析 LLM 卡片 JSON 失败: error={}, raw={}", e.getMessage(), llmResponse, e);
        }
        return cards;
    }
}
