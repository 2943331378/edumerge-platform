package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.Document;
import com.edumerge.entity.Session;
import com.edumerge.mapper.DocumentMapper;
import com.edumerge.mapper.SessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class SessionService {

    private final SessionMapper sessionMapper;
    private final DocumentMapper documentMapper;

    @Autowired
    public SessionService(SessionMapper sessionMapper, DocumentMapper documentMapper) {
        this.sessionMapper = sessionMapper;
        this.documentMapper = documentMapper;
    }

    public Session create(Long userId, Long docId, String title) {
        Session session = Session.builder()
                .userId(userId).docId(docId).title(title).status("ACTIVE").build();
        sessionMapper.insert(session);
        log.info("会话已创建: id={}, docId={}, title={}", session.getId(), docId, title);
        return session;
    }

    public List<Session> listByUserId(Long userId) {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<Session>()
                        .eq(Session::getUserId, userId)
                        .orderByDesc(Session::getCreatedAt));
    }

    public Session getById(Long id) {
        return sessionMapper.selectById(id);
    }

    public Long resolveDocId(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("会话不存在: " + sessionId);
        return session.getDocId();
    }

    public String resolveDocUuid(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("会话不存在: " + sessionId);
        Document doc = documentMapper.selectById(session.getDocId());
        if (doc == null) throw new IllegalArgumentException("关联文档不存在: " + session.getDocId());
        return doc.getDocumentId();
    }
}
