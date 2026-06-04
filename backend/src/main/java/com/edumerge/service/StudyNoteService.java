package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.ai.AiNoteGenerator;
import com.edumerge.entity.Document;
import com.edumerge.entity.StudyNote;
import com.edumerge.mapper.StudyNoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习笔记业务服务 — CRUD + AI 生成
 */
@Slf4j
@Service
public class StudyNoteService {

    private final StudyNoteMapper studyNoteMapper;
    private final AiNoteGenerator aiNoteGenerator;
    private final DocumentService documentService;
    private final CardDeckService cardDeckService;

    @Autowired
    public StudyNoteService(StudyNoteMapper studyNoteMapper,
                            AiNoteGenerator aiNoteGenerator,
                            DocumentService documentService,
                            CardDeckService cardDeckService) {
        this.studyNoteMapper = studyNoteMapper;
        this.aiNoteGenerator = aiNoteGenerator;
        this.documentService = documentService;
        this.cardDeckService = cardDeckService;
    }

    // ═══════ CRUD ═══════

    @Transactional(readOnly = true)
    public StudyNote getByDocId(Long docId) {
        return studyNoteMapper.selectOne(
                new LambdaQueryWrapper<StudyNote>()
                        .eq(StudyNote::getDocId, docId)
                        .orderByDesc(StudyNote::getCreatedAt)
                        .last("LIMIT 1"));
    }

    @Transactional(readOnly = true)
    public List<StudyNote> listByDocId(Long docId) {
        return studyNoteMapper.selectList(
                new LambdaQueryWrapper<StudyNote>()
                        .eq(StudyNote::getDocId, docId)
                        .orderByDesc(StudyNote::getCreatedAt));
    }

    @Transactional
    public StudyNote create(Long docId, Long deckId, String title, String content, String sourceSummary, String requirements) {
        StudyNote note = StudyNote.builder()
                .docId(docId)
                .deckId(deckId)
                .title(title)
                .content(content)
                .sourceSummary(sourceSummary)
                .requirements(requirements)
                .build();
        studyNoteMapper.insert(note);
        log.info("学习笔记已创建: id={}, docId={}, deckId={}, contentLen={}",
                note.getId(), docId, deckId, content.length());
        return note;
    }

    @Transactional
    public StudyNote update(Long id, String content, String title) {
        StudyNote note = studyNoteMapper.selectById(id);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在: " + id);
        }
        if (content != null) {
            note.setContent(content);
        }
        if (title != null) {
            note.setTitle(title);
        }
        note.setUpdatedAt(java.time.LocalDateTime.now());
        studyNoteMapper.updateById(note);
        log.info("学习笔记已更新: id={}", id);
        return note;
    }

    @Transactional
    public void deleteByDocId(Long docId) {
        studyNoteMapper.delete(
                new LambdaQueryWrapper<StudyNote>()
                        .eq(StudyNote::getDocId, docId));
        log.info("旧学习笔记已清理: docId={}", docId);
    }

    // ═══════ AI 生成 ═══════

    /**
     * 生成学习笔记
     *
     * @return 包含笔记元数据的 Map
     * @throws IllegalArgumentException 参数校验失败
     * @throws IllegalStateException    生成失败
     */
    @Transactional
    public Map<String, Object> generate(Long docId, String requirements, String sectionContext) {
        Document doc = documentService.getById(docId);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        if (!"COMPLETED".equalsIgnoreCase(doc.getStatus())) {
            throw new IllegalArgumentException("文档尚未完成向量化，当前状态: " + doc.getStatus());
        }
        if (doc.getDocumentId() == null || doc.getDocumentId().isBlank()) {
            throw new IllegalArgumentException("文档缺少向量检索标识，无法生成学习笔记");
        }

        log.info("开始生成学习笔记: docId={}, docUuid={}, sectionContext={}", docId, doc.getDocumentId(),
                sectionContext != null ? sectionContext.substring(0, Math.min(100, sectionContext.length())) : "null");

        AiNoteGenerator.StudyNoteResult generated = aiNoteGenerator.generate(
                docId, doc.getDocumentId(), requirements, sectionContext);

        if (!generated.isSuccess()) {
            throw new IllegalStateException("学习笔记生成失败: 未从文档中提取到足够内容");
        }

        // 持久化 — 创建 deck + study_note 记录
        com.edumerge.entity.CardDeck deck = cardDeckService.create(docId, "NOTE", generated.getTitle());
        StudyNote saved = create(docId, deck.getId(), generated.getTitle(),
                generated.getContent(), generated.getSourceSummary(), generated.getRequirements());
        log.info("学习笔记已持久化: docId={}, deckId={}", docId, deck.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deckId", deck.getId());
        data.put("docId", docId);
        data.put("title", generated.getTitle());
        data.put("content", generated.getContent());
        data.put("sourceSummary", generated.getSourceSummary());
        data.put("requirements", generated.getRequirements());
        data.put("createdAt", saved.getCreatedAt() != null ? saved.getCreatedAt().toString() : null);
        return data;
    }

    // ═══════ 数据转换 ═══════

    /** 实体 → Map（控制器响应用） */
    public Map<String, Object> toMap(StudyNote note) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", note.getId());
        data.put("deckId", note.getDeckId());
        data.put("docId", note.getDocId());
        data.put("title", note.getTitle());
        data.put("content", note.getContent());
        data.put("sourceSummary", note.getSourceSummary());
        data.put("requirements", note.getRequirements());
        data.put("createdAt", note.getCreatedAt() != null ? note.getCreatedAt().toString() : null);
        return data;
    }
}
