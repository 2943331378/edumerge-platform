package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edumerge.entity.Conversation;
import com.edumerge.mapper.ConversationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;

    @Autowired
    public ConversationService(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    /** 确保会话存在 — 不存在则创建 (关联文档), 存在时更新 docId */
    public Conversation ensure(String sessionId, Long userId, String title, Long docId) {
        Conversation existing = getBySessionId(sessionId);
        if (existing != null) {
            // 回填 docId（兼容历史数据 docId 为 NULL 的情况）
            if (existing.getDocId() == null && docId != null) {
                existing.setDocId(docId);
                conversationMapper.updateById(existing);
            }
            return existing;
        }
        Conversation c = Conversation.builder()
                .sessionId(sessionId).userId(userId).docId(docId).title(title)
                .deleted(0)
                .build();
        conversationMapper.insert(c);
        log.info("对话会话已创建: sessionId={}, docId={}, title={}", sessionId, docId, title);
        return c;
    }

    /** 更新会话标题 */
    public void updateTitle(String sessionId, String title) {
        conversationMapper.update(null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId)
                        .set(Conversation::getTitle, title));
    }

    /** 列出用户的所有会话 */
    public List<Conversation> listByUserId(Long userId) {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getCreatedAt));
    }

    /** 按文档列出会话 */
    public List<Conversation> listByDocId(Long userId, Long docId) {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .eq(docId != null, Conversation::getDocId, docId)
                        .orderByDesc(Conversation::getCreatedAt));
    }

    /** 按 sessionId 查找 */
    public Conversation getBySessionId(String sessionId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId));
    }

    /** 删除会话 (软删除) */
    public void delete(String sessionId) {
        conversationMapper.delete(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId));
        log.info("对话会话已删除: sessionId={}", sessionId);
    }
}
