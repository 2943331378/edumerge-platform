package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.ChatHistory;
import com.edumerge.mapper.ChatHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ChatHistoryService {

    private final ChatHistoryMapper chatHistoryMapper;

    @Autowired
    public ChatHistoryService(ChatHistoryMapper chatHistoryMapper) {
        this.chatHistoryMapper = chatHistoryMapper;
    }

    /** 保存对话记录 */
    public ChatHistory save(String query, String response, int retrievedCount, String sessionId) {
        ChatHistory history = ChatHistory.builder()
                .userId(1L)
                .sessionId(sessionId)
                .query(query)
                .response(response)
                .retrievedDocuments(retrievedCount)
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
