package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.StudyNote;
import com.edumerge.mapper.StudyNoteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class StudyNoteService {

    private final StudyNoteMapper studyNoteMapper;

    @Autowired
    public StudyNoteService(StudyNoteMapper studyNoteMapper) {
        this.studyNoteMapper = studyNoteMapper;
    }

    public StudyNote getByDocId(Long docId) {
        return studyNoteMapper.selectOne(
                new LambdaQueryWrapper<StudyNote>()
                        .eq(StudyNote::getDocId, docId)
                        .orderByDesc(StudyNote::getCreatedAt)
                        .last("LIMIT 1"));
    }

    public List<StudyNote> listByDocId(Long docId) {
        return studyNoteMapper.selectList(
                new LambdaQueryWrapper<StudyNote>()
                        .eq(StudyNote::getDocId, docId)
                        .orderByDesc(StudyNote::getCreatedAt));
    }

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

    public void deleteByDocId(Long docId) {
        studyNoteMapper.delete(
                new LambdaQueryWrapper<StudyNote>()
                        .eq(StudyNote::getDocId, docId));
        log.info("旧学习笔记已清理: docId={}", docId);
    }
}
