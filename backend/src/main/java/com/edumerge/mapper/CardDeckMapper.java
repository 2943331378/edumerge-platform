package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.CardDeck;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CardDeckMapper extends BaseMapper<CardDeck> {

    /** 按 deckId 分组统计测验次数 */
    @Select("SELECT deck_id AS deckId, COUNT(*) AS cnt " +
            "FROM quiz_attempts " +
            "WHERE user_id = #{userId} AND deck_id IS NOT NULL " +
            "GROUP BY deck_id")
    List<Map<String, Object>> countAttemptsByDeck(@Param("userId") Long userId);
}
