package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.Quiz;
import org.apache.ibatis.annotations.Mapper;

/**
 * 测试题 Mapper
 * 继承 MyBatis-Plus BaseMapper, 自动获得 CRUD 方法
 */
@Mapper
public interface QuizMapper extends BaseMapper<Quiz> {
}
