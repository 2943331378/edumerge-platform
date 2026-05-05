package com.edumerge.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.ChatHistory;
import com.edumerge.mapper.ChatHistoryMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j ChatMemoryStore — 将对话历史持久化到 MySQL chat_history 表
 */
@Slf4j
public class JpaChatMemoryStore implements ChatMemoryStore {

    private final ChatHistoryMapper chatHistoryMapper;

    public JpaChatMemoryStore(ChatHistoryMapper chatHistoryMapper) {
        this.chatHistoryMapper = chatHistoryMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        List<ChatHistory> records = chatHistoryMapper.selectList(
                new LambdaQueryWrapper<ChatHistory>()
                        .eq(ChatHistory::getSessionId, sessionId)
                        .orderByAsc(ChatHistory::getCreatedAt));
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatHistory h : records) {
            messages.add(new UserMessage(h.getQuery()));
            messages.add(new AiMessage(h.getResponse()));
        }
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        // 统计 DB 中已有记录数 (每条记录 = 1对 User+AI)
        Long existingCount = chatHistoryMapper.selectCount(
                new LambdaQueryWrapper<ChatHistory>()
                        .eq(ChatHistory::getSessionId, sessionId));

        // 消息成对存储: UserMessage → query, AiMessage → response
        int pairCount = messages.size() / 2;
        for (int i = (int) (long) existingCount; i < pairCount; i++) {
            int userIdx = i * 2;
            int aiIdx = userIdx + 1;
            if (aiIdx >= messages.size()) break;
            ChatMessage userMsg = messages.get(userIdx);
            ChatMessage aiMsg = messages.get(aiIdx);
            if (userMsg instanceof UserMessage && aiMsg instanceof AiMessage) {
                ChatHistory record = ChatHistory.builder()
                        .userId(1L)
                        .sessionId(sessionId)
                        .query(((UserMessage) userMsg).singleText())
                        .response(((AiMessage) aiMsg).text())
                        .retrievedDocuments(0)
                        .build();
                chatHistoryMapper.insert(record);
            }
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        chatHistoryMapper.delete(
                new LambdaQueryWrapper<ChatHistory>()
                        .eq(ChatHistory::getSessionId, sessionId));
        log.info("已清空会话历史: sessionId={}", sessionId);
    }
}
