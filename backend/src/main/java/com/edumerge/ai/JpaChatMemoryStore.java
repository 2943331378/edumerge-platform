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
        if (sessionId.isBlank()) return;

        // 先不带 @TableLogic 过滤查一次原始数量
        Long rawCount = chatHistoryMapper.selectCount(
                new LambdaQueryWrapper<ChatHistory>()
                        .eq(ChatHistory::getSessionId, sessionId)
                        .eq(ChatHistory::getDeleted, 0)); // 显式指定 deleted=0, 绕过 @TableLogic NULL 陷阱

        // 只统计非 SystemMessage 的对数
        long userMsgCount = messages.stream().filter(m -> m instanceof UserMessage).count();
        long aiMsgCount = messages.stream().filter(m -> m instanceof AiMessage).count();

        log.info("ChatMemory persist: sessionId={}, rawCount={}, userMsgs={}, aiMsgs={}, total={}",
                sessionId, rawCount, userMsgCount, aiMsgCount, messages.size());

        // 增量插入 — 跳过已持久化的前 N 对
        int pairCount = (int) Math.min(userMsgCount, aiMsgCount);
        for (int i = (int) (long) rawCount; i < pairCount; i++) {
            // 收集第 i 对 User + AI
            int userFound = -1, aiFound = -1;
            int uIdx = 0, aIdx = 0;
            for (int j = 0; j < messages.size(); j++) {
                ChatMessage m = messages.get(j);
                if (m instanceof UserMessage && uIdx++ == i) userFound = j;
                if (m instanceof AiMessage && aIdx++ == i) aiFound = j;
            }
            if (userFound < 0 || aiFound < 0) break;
            UserMessage userMsg = (UserMessage) messages.get(userFound);
            AiMessage aiMsg = (AiMessage) messages.get(aiFound);
            ChatHistory record = ChatHistory.builder()
                    .userId(1L)
                    .sessionId(sessionId)
                    .query(userMsg.singleText())
                    .response(aiMsg.text())
                    .retrievedDocuments(0)
                    .deleted(0)
                    .build();
            int rows = chatHistoryMapper.insert(record);
            log.info("ChatHistory INSERT result={}, id={}, sessionId={}, query={}",
                    rows, record.getId(), sessionId,
                    userMsg.singleText().substring(0, Math.min(40, userMsg.singleText().length())));

            // 立即验证: 用原始 SQL 查询确认写入
            Long verifyCount = chatHistoryMapper.selectCount(
                    new LambdaQueryWrapper<ChatHistory>()
                            .eq(ChatHistory::getSessionId, sessionId)
                            .eq(ChatHistory::getDeleted, 0));
            log.info("Post-insert verify: sessionId={}, count={}", sessionId, verifyCount);
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
