package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.ChatHistory;
import com.edumerge.mapper.ChatHistoryMapper;
import com.edumerge.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ChatHistoryService {

    private final ChatHistoryMapper chatHistoryMapper;
    private final ConversationService conversationService;

    @Autowired
    public ChatHistoryService(ChatHistoryMapper chatHistoryMapper,
                               ConversationService conversationService) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.conversationService = conversationService;
    }

    /** 保存对话记录, 同时确保 conversation 会话存在 */
    public ChatHistory save(String query, String response, int retrievedCount, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            // 首次对话时自动创建 conversation 记录
            String title = query.length() > 40 ? query.substring(0, 40) + "..." : query;
            conversationService.ensure(sessionId, 1L, title);
        }
        ChatHistory history = ChatHistory.builder()
                .userId(1L)
                .sessionId(sessionId)
                .query(query)
                .response(response)
                .retrievedDocuments(retrievedCount)
                .deleted(0)
                .build();
        chatHistoryMapper.insert(history);
        return history;
    }

    /** 按会话查询最近对话历史 */
    public List<ChatHistory> listBySession(String sessionId, int limit) {
        return chatHistoryMapper.selectList(
                new LambdaQueryWrapper<ChatHistory>()
                        .eq(sessionId != null, ChatHistory::getSessionId, sessionId)
                        .orderByDesc(ChatHistory::getCreatedAt)
                        .last("LIMIT " + limit));
    }
}
