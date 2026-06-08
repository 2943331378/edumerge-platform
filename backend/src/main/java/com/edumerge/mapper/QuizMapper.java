package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.Quiz;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 测试题 Mapper
 * 继承 MyBatis-Plus BaseMapper, 自动获得 CRUD 方法
 */
@Mapper
public interface QuizMapper extends BaseMapper<Quiz> {

    /** 统计用户的测验题总数 */
    @Select("SELECT COUNT(*) FROM quizzes WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") Long userId);
}
