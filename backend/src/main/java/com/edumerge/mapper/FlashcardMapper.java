package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.Flashcard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 学习卡片 Mapper
 * 继承 MyBatis-Plus BaseMapper, 自动获得 CRUD 方法
 */
@Mapper
public interface FlashcardMapper extends BaseMapper<Flashcard> {

    /** 按文档分组统计到期闪卡数 */
    @Select("SELECT doc_id AS docId, COUNT(*) AS cnt " +
            "FROM flashcards " +
            "WHERE user_id = #{userId} AND status = 'ACTIVE' AND deleted = 0 " +
            "AND (next_review_at <= #{now} OR next_review_at IS NULL) " +
            "GROUP BY doc_id")
    List<Map<String, Object>> countDueByDoc(@Param("userId") Long userId,
                                             @Param("now") LocalDateTime now);

    /** 按文档分组统计闪卡总数和已复习数 */
    @Select("SELECT doc_id AS docId, " +
            "COUNT(*) AS total_cards, " +
            "SUM(CASE WHEN review_count > 0 THEN 1 ELSE 0 END) AS reviewed_cards " +
            "FROM flashcards " +
            "WHERE user_id = #{userId} AND deleted = 0 AND doc_id IS NOT NULL " +
            "GROUP BY doc_id")
    List<Map<String, Object>> countByDoc(@Param("userId") Long userId);
}
