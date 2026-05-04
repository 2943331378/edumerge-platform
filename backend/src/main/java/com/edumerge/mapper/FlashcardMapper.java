package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.Flashcard;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学习卡片 Mapper
 * 继承 MyBatis-Plus BaseMapper, 自动获得 CRUD 方法
 */
@Mapper
public interface FlashcardMapper extends BaseMapper<Flashcard> {
}
