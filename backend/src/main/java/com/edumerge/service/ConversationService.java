package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edumerge.entity.Conversation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.edumerge.mapper.ConversationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;

    @Autowired
    public ConversationService(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    /** 确保会话存在 — 不存在则创建 (关联文档), 存在时更新 docId/docUuid */
    @Transactional
    public Conversation ensure(String sessionId, Long userId, String title, Long docId, String docUuid) {
        Conversation existing = getBySessionId(sessionId);
        if (existing != null) {
            boolean dirty = false;
            if (existing.getDocId() == null && docId != null) {
                existing.setDocId(docId);
                dirty = true;
            }
            if (existing.getDocUuid() == null && docUuid != null && !docUuid.isBlank()) {
                existing.setDocUuid(docUuid);
                dirty = true;
            }
            if (dirty) conversationMapper.updateById(existing);
            return existing;
        }
        Conversation c = Conversation.builder()
                .sessionId(sessionId).userId(userId).docId(docId).docUuid(docUuid).title(title)
                .exchangeCount(0).deleted(0)
                .build();
        conversationMapper.insert(c);
        log.info("对话会话已创建: sessionId={}, docId={}, docUuid={}, title={}", sessionId, docId, docUuid, title);
        return c;
    }

    /** 自增对话轮数并返回最新值 (用于 FlowNote 自动提取判断) */
    @Transactional
    public int incrementExchangeCount(String sessionId) {
        conversationMapper.update(null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId)
                        .setSql("exchange_count = exchange_count + 1"));
        Conversation c = getBySessionId(sessionId);
        return c != null && c.getExchangeCount() != null ? c.getExchangeCount() : 0;
    }

    /** 更新会话标题 (带归属校验) */
    @Transactional
    public void updateTitle(String sessionId, String title, Long userId) {
        Conversation c = getBySessionId(sessionId);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此对话");
        }
        conversationMapper.update(null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId)
                        .set(Conversation::getTitle, title));
    }

    /** 列出用户的所有会话 */
    @Transactional(readOnly = true)
    public List<Conversation> listByUserId(Long userId) {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getCreatedAt));
    }

    /** 按文档列出会话 */
    @Transactional(readOnly = true)
    public List<Conversation> listByDocId(Long userId, Long docId) {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .eq(docId != null, Conversation::getDocId, docId)
                        .orderByDesc(Conversation::getCreatedAt));
    }

    /** 按 sessionId 查找 */
    @Transactional(readOnly = true)
    public Conversation getBySessionId(String sessionId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId));
    }

    /** 删除会话 (软删除，带归属校验) */
    @Transactional
    public void delete(String sessionId, Long userId) {
        Conversation c = getBySessionId(sessionId);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此对话");
        }
        conversationMapper.delete(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId));
        log.info("对话会话已删除: sessionId={}", sessionId);
    }
}
