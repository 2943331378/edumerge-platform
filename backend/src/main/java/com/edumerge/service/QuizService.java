package com.edumerge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumerge.entity.Quiz;
import com.edumerge.mapper.QuizMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 测试题 CRUD 服务 (AI 生成逻辑已抽离至 ai.AiQuizGenerator)
 */
@Slf4j
@Service
public class QuizService {

    private final QuizMapper quizMapper;

    @Autowired
    public QuizService(QuizMapper quizMapper) {
        this.quizMapper = quizMapper;
    }

    public List<Quiz> listByDocId(Long docId) {
        return quizMapper.selectList(
                new LambdaQueryWrapper<Quiz>()
                        .eq(Quiz::getDocId, docId)
                        .orderByAsc(Quiz::getId));
    }

    public List<Quiz> listByDocIdAndType(Long docId, String quizType) {
        return quizMapper.selectList(
                new LambdaQueryWrapper<Quiz>()
                        .eq(Quiz::getDocId, docId)
                        .eq(Quiz::getQuizType, quizType)
                        .orderByAsc(Quiz::getId));
    }

    public Quiz create(Quiz quiz) {
        quizMapper.insert(quiz);
        log.info("测试题已创建: id={}, docId={}", quiz.getId(), quiz.getDocId());
        return quiz;
    }

    public void batchCreate(List<Quiz> quizzes) {
        quizMapper.insert(quizzes, 50);
        log.info("批量创建测试题完成: 数量={}", quizzes.size());
    }
}
