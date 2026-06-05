package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.ai.AiFlowNoteGenerator;
import com.edumerge.entity.ChatHistory;
import com.edumerge.entity.FlowNote;
import com.edumerge.mapper.ChatHistoryMapper;
import com.edumerge.mapper.FlowNoteMapper;
import com.edumerge.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class FlowNoteService {

    private final FlowNoteMapper flowNoteMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final AiFlowNoteGenerator aiFlowNoteGenerator;

    public FlowNoteService(FlowNoteMapper flowNoteMapper,
                           ChatHistoryMapper chatHistoryMapper,
                           AiFlowNoteGenerator aiFlowNoteGenerator) {
        this.flowNoteMapper = flowNoteMapper;
        this.chatHistoryMapper = chatHistoryMapper;
        this.aiFlowNoteGenerator = aiFlowNoteGenerator;
    }

    /** 按文档 + 用户查询所有条目 */
    @Transactional(readOnly = true)
    public List<FlowNote> listByDocId(Long docId, Long userId) {
        return flowNoteMapper.selectList(
                new LambdaQueryWrapper<FlowNote>()
                        .eq(FlowNote::getDocId, docId)
                        .eq(FlowNote::getUserId, userId)
                        .orderByDesc(FlowNote::getCreatedAt));
    }

    /** 按文档 + 用户 + 分类筛选 */
    @Transactional(readOnly = true)
    public List<FlowNote> listByDocIdAndCategory(Long docId, Long userId, String category) {
        return flowNoteMapper.selectList(
                new LambdaQueryWrapper<FlowNote>()
                        .eq(FlowNote::getDocId, docId)
                        .eq(FlowNote::getUserId, userId)
                        .eq(category != null, FlowNote::getCategory, category)
                        .orderByDesc(FlowNote::getCreatedAt));
    }

    /** 创建条目 */
    @Transactional
    public FlowNote create(FlowNote note) {
        flowNoteMapper.insert(note);
        log.info("FlowNote 条目已创建: id={}, category={}", note.getId(), note.getCategory());
        return note;
    }

    /** 批量创建 */
    @Transactional
    public void batchCreate(List<FlowNote> notes) {
        if (notes.isEmpty()) return;
        flowNoteMapper.insert(notes, 50);
        log.info("FlowNote 批量创建完成: 数量={}", notes.size());
    }

    /** 更新条目 */
    @Transactional
    public void update(Long id, FlowNote note) {
        verifyOwnership(id);
        note.setId(id);
        flowNoteMapper.updateById(note);
    }

    /** 删除条目 */
    @Transactional
    public void delete(Long id) {
        verifyOwnership(id);
        flowNoteMapper.deleteById(id);
    }

    /** 标记已复习 */
    @Transactional
    public void markReviewed(Long id) {
        verifyOwnership(id);
        FlowNote note = flowNoteMapper.selectById(id);
        if (note != null) {
            note.setIsReviewed(1);
            note.setReviewedAt(LocalDateTime.now());
            flowNoteMapper.updateById(note);
        }
    }

    private void verifyOwnership(Long noteId) {
        FlowNote note = flowNoteMapper.selectById(noteId);
        if (note == null) throw new IllegalArgumentException("条目不存在: " + noteId);
        Long userId = SecurityUtils.getCurrentUserId();
        if (!userId.equals(note.getUserId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权操作此条目");
        }
    }

    /** 统计 */
    public Map<String, Object> stats(Long docId, Long userId) {
        List<FlowNote> all = listByDocId(docId, userId);
        long reviewed = all.stream().filter(n -> n.getIsReviewed() != null && n.getIsReviewed() == 1).count();
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (FlowNote n : all) {
            byCategory.merge(n.getCategory(), 1L, Long::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", all.size());
        result.put("reviewed", reviewed);
        result.put("reviewRate", all.isEmpty() ? 0 : (double) reviewed / all.size());
        result.put("byCategory", byCategory);
        return result;
    }

    /** AI 从对话历史中提取 FlowNote 条目 */
    public List<FlowNote> extractFromChat(Long docId, String docUuid, Long userId,
                                           String sessionId, int maxExchanges) {
        // 1. 获取最近 N 轮对话记录
        List<ChatHistory> history = chatHistoryMapper.selectList(
                new LambdaQueryWrapper<ChatHistory>()
                        .eq(sessionId != null, ChatHistory::getSessionId, sessionId)
                        .eq(ChatHistory::getUserId, userId)
                        .orderByDesc(ChatHistory::getCreatedAt)
                        .last("LIMIT " + maxExchanges));

        if (history.isEmpty()) {
            log.info("FlowNote 提取: 无对话记录, docId={}", docId);
            return List.of();
        }

        // 按时间正序排列
        List<ChatHistory> ordered = new ArrayList<>(history);
        ordered.sort(Comparator.comparing(ChatHistory::getCreatedAt));

        // 2. 交给 AI 生成器提取
        List<AiFlowNoteGenerator.FlowNoteItem> items =
                aiFlowNoteGenerator.extract(ordered, docUuid);

        // 3. 逐个持久化
        List<FlowNote> saved = new ArrayList<>();
        for (AiFlowNoteGenerator.FlowNoteItem item : items) {
            FlowNote note = FlowNote.builder()
                    .userId(userId)
                    .docId(docId)
                    .sessionId(sessionId)
                    .category(item.getCategory())
                    .title(item.getTitle())
                    .content(item.getContent())
                    .sourceSegment(item.getSourceSegment())
                    .sourceType("CHAT_EXTRACTED")
                    .isReviewed(0)
                    .build();
            flowNoteMapper.insert(note);
            saved.add(note);
        }

        log.info("FlowNote AI 提取完成: docId={}, 条目数={}", docId, saved.size());
        return saved;
    }
}
