package com.edumerge.ai;

import com.edumerge.entity.ChatHistory;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiFlowNoteGenerator extends AiGeneratorBase {

    @Autowired
    private ChatModel chatLanguageModel;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FlowNoteItem {
        private String category;
        private String title;
        private String content;
        private String sourceSegment;
    }

    /**
     * 从对话历史中提取结构化 FlowNote 条目
     * @param history    最近 N 轮对话记录（按时间正序）
     * @param docUuid    文档 UUID，用于检索文档上下文
     */
    public List<FlowNoteItem> extract(List<ChatHistory> history, String docUuid) {
        // 1. 拼装对话记录文本
        String conversationText = history.stream()
                .map(h -> "用户: " + h.getQuery() + "\nAI: " + h.getResponse())
                .collect(Collectors.joining("\n\n"));

        // 2. 从文档检索相关上下文（以最后一个问题作为语义查询）
        String lastQuery = history.isEmpty() ? "核心内容" : history.get(history.size() - 1).getQuery();
        var matches = retrieveTopChunks(docUuid, 10, lastQuery);
        String docContext = buildContext(matches);

        // 3. 构建提示词并调用 LLM
        String raw = callLLM(conversationText, docContext);
        String json = extractJsonArray(raw);

        // 4. 解析 JSON
        try {
            return objectMapper.readValue(json, new TypeReference<List<FlowNoteItem>>() {});
        } catch (Exception e) {
            log.warn("FlowNote JSON 解析失败，尝试修复: {}", e.getMessage());
            // 降级：返回单条原始摘要
            FlowNoteItem fallback = new FlowNoteItem();
            fallback.setCategory("KEY_POINT");
            fallback.setTitle("对话摘要");
            fallback.setContent(raw.length() > 1000 ? raw.substring(0, 1000) : raw);
            fallback.setSourceSegment(null);
            return List.of(fallback);
        }
    }

    private String callLLM(String conversationText, String docContext) {
        String template = """
                你是一个严谨的学习日志整理助手。基于对话记录和文档内容，提取结构化笔记。

                # 要求
                - 仅基于文档和对话内容，不编造信息
                - 使用简体中文
                - 如果对话中没有某类内容，可跳过该类
                - 至少提取2条，最多8条

                # 分类（四选一）
                - KEY_POINT: 核心知识点、关键概念
                - QUESTION: 用户提出的重要问题及回答要点
                - EXAMPLE: 有价值的例子、类比或应用场景
                - REVIEW: 需要后续巩固复习的内容

                # 每条笔记字段
                - category: 分类标识
                - title: 条目标题（不超过30字）
                - content: Markdown正文（不超过300字）
                - sourceSegment: 相关文档原文片段（可选）

                # 输出格式（仅输出JSON数组）
                [{"category":"KEY_POINT","title":"...","content":"...","sourceSegment":"..."}]

                【文档内容】
                {DCONTEXT}

                【对话记录】
                {CHAT}
                """;

        String prompt = template
                .replace("{DCONTEXT}", docContext.isBlank() ? "（无）" : docContext)
                .replace("{CHAT}", conversationText);

        SystemMessage system = new SystemMessage(prompt);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(system);
        messages.add(new UserMessage("请基于以上对话记录和文档内容，提取结构化学习笔记。"));

        ChatResponse response = AI_CIRCUIT_BREAKER.execute(() -> chatLanguageModel.chat(messages));
        return response.aiMessage().text();
    }
}
