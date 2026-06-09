package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.Document;
import com.edumerge.entity.Session;
import com.edumerge.mapper.DocumentMapper;
import com.edumerge.mapper.SessionMapper;
import com.edumerge.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Transactional
    public Session create(Long userId, Long docId, String title) {
        Session session = Session.builder()
                .userId(userId).docId(docId).title(title).status("ACTIVE").build();
        sessionMapper.insert(session);
        log.info("会话已创建: id={}, docId={}, title={}", session.getId(), docId, title);
        return session;
    }

    @Transactional(readOnly = true)
    public List<Session> listByUserId(Long userId) {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<Session>()
                        .eq(Session::getUserId, userId)
                        .orderByDesc(Session::getCreatedAt));
    }

    @Transactional(readOnly = true)
    public Session getById(Long id) {
        return sessionMapper.selectById(id);
    }

    @Transactional(readOnly = true)
    public Long resolveDocId(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("会话不存在: " + sessionId);
        return session.getDocId();
    }

    @Transactional(readOnly = true)
    public String resolveDocUuid(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("会话不存在: " + sessionId);
        Document doc = documentMapper.selectById(session.getDocId());
        if (doc == null) throw new IllegalArgumentException("关联文档不存在: " + session.getDocId());
        return doc.getDocumentId();
    }

    @Transactional
    public void deleteById(Long id) {
        Session session = sessionMapper.selectById(id);
        if (session == null) throw new IllegalArgumentException("会话不存在: " + id);
        if (!session.getUserId().equals(SecurityUtils.getCurrentUserId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权删除此会话");
        }
        sessionMapper.deleteById(id);
        log.info("会话已删除: id={}", id);
    }

    /** PROCESSING 超时阈值 — 超过此时间自动标记为 FAILED */
    private static final int PROCESSING_TIMEOUT_MINUTES = 10;

    /**
     * 查询用户会话列表（含文档关联信息）
     * 同时检查 PROCESSING 超时文档并自动标记为 FAILED
     *
     * @return 每项含 id, docId, docUuid, title, status, fileName, docStatus, chunkCount, vectorCount, pageCount, createdAt
     */
    @Transactional
    public List<Map<String, Object>> listWithDocInfo(Long userId) {
        List<Session> sessions = listByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Session s : sessions) {
            Document doc = documentMapper.selectById(s.getDocId());

            // PROCESSING 超时自动重置 — 消费者崩溃后文档不会永远卡住
            if (doc != null && "PROCESSING".equals(doc.getStatus())
                    && doc.getUpdatedAt() != null
                    && doc.getUpdatedAt().isBefore(LocalDateTime.now().minusMinutes(PROCESSING_TIMEOUT_MINUTES))) {
                Document update = new Document();
                update.setId(doc.getId());
                update.setStatus("FAILED");
                update.setStatusMessage("处理超时（超过 " + PROCESSING_TIMEOUT_MINUTES + " 分钟），请重试");
                documentMapper.updateById(update);
                doc = documentMapper.selectById(doc.getId());
                log.warn("文档 PROCESSING 超时, 已自动标记为 FAILED: docId={}", s.getDocId());
            }

            Map<String, Object> item = new HashMap<>();
            item.put("id", s.getId());
            item.put("docId", s.getDocId());
            item.put("docUuid", doc != null ? doc.getDocumentId() : null);
            item.put("title", s.getTitle());
            item.put("status", s.getStatus());
            item.put("fileName", doc != null ? doc.getFileName() : null);
            item.put("fileType", doc != null ? doc.getFileType() : null);
            item.put("docStatus", doc != null ? doc.getStatus() : null);
            item.put("chunkCount", doc != null ? doc.getChunkCount() : null);
            item.put("vectorCount", doc != null ? doc.getVectorCount() : null);
            item.put("pageCount", doc != null ? doc.getPageCount() : null);
            item.put("folderId", doc != null ? doc.getFolderId() : null);
            item.put("createdAt", s.getCreatedAt());
            result.add(item);
        }
        return result;
    }
}
