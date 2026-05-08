package com.edumerge.ai;

import com.edumerge.entity.Flashcard;
import com.edumerge.mapper.FlashcardMapper;
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
 * AI 学习卡片生成器 (架构红线: LangChain4j 隔离在 ai 包)
 */
@Slf4j
@Service
public class AiFlashcardGenerator extends AiGeneratorBase {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private FlashcardMapper flashcardMapper;

    @Autowired
    private CardDeckService cardDeckService;

    /** 根据文档内容自动生成学习卡片 (每次生成创建一个 Deck) */
    public List<Flashcard> generate(Long docId, Long userId, String docUuid) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveTopChunks(docUuid, 10,
                "核心知识点 关键概念 重要内容 定义 原理 方法 总结 key concepts important content definition principles methods summary");
        if (matches.isEmpty()) { log.warn("未检索到文档块: docId={}", docId); return List.of(); }

        String context = buildContext(matches);
        log.info("提取上下文完成: docId={}, 块数={}", docId, matches.size());

        String llmResponse = callLLM(context);
        log.info("LLM 卡片生成响应: 长度={} 字符", llmResponse.length());

        // 创建卡片组, 将本次生成的卡片绑定到该组
        Long deckId = cardDeckService.create(docId, "FLASHCARD").getId();
        return parseAndSave(llmResponse, docId, userId, deckId, matches);
    }

    private String callLLM(String context) {
        String template = """
                你是一个严谨的 AI 学习导师。请分析提供的文档片段，提取5个核心知识点并转化为学习卡片。

                # 绝对禁止 (违反将导致严重质量问题)
                1. **禁止元数据问题**: 严禁提问文档结构、章节归属、页码位置、模块划分等。
                   反面示例(禁止):
                   - "'并发处理'属于哪个章节？"
                   - "该规范位于文档的第几部分？"
                   - "这段内容出现在哪个标题下？"
                   - "文档中第X条规则是什么？" (只问序号不问含义)
                2. **禁止废话问题**: 严禁提问可以用"是/否"回答、或答案仅为一个术语名称的问题。
                   反面示例(禁止):
                   - "'XXX'是重要的概念吗？" — 答案仅是"是"
                   - "文档是否提到了YYY？" — 可用"是/否"回答

                # 优先级要求
                1. **文档为事实依据**: 每张卡片必须基于提供的文档上下文, 严禁编造文档外的知识。
                2. **提取核心概念**: 聚焦"业务概念"、"技术原理"、"定义"或"规范规则"。
                3. **中文输出**: question 和 answer 必须使用简体中文；如果文档是英文，请基于英文原文翻译、归纳和解释。
                4. **术语保留**: 英文关键术语首次出现时保留英文原词，例如"自适应学习（adaptive learning）"。
                   正面示例(应模仿):
                   - "Java 中保证并发安全的三个核心原则是什么？"
                   - "什么是CAP定理？它在分布式系统设计中如何应用？"
                   - "RESTful API 设计规范中，资源的命名应遵循什么规则？"
                5. **精准简洁**: question 清晰明确, answer 准确有信息量, 单条卡片不超过200字。

                # 输出格式
                [{"question": "知识点问题1", "answer": "对应答案1"}]

                # 文档上下文
                {CONTEXT}
                """;
        SystemMessage system = new SystemMessage(template.replace("{CONTEXT}", context));

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上文档内容，生成5张学习卡片。"));
        Response<AiMessage> response = chatLanguageModel.generate(messages);
        return response.content().text();
    }

    private List<Flashcard> parseAndSave(String llmResponse, Long docId, Long userId,
                                          Long deckId, List<EmbeddingMatch<TextSegment>> matches) {
        List<Flashcard> cards = new ArrayList<>();
        try {
            String json = extractJsonArray(llmResponse);
            List<Map<String, String>> items = objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
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
}
