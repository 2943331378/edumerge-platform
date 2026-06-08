package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.FlashcardReviewLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface FlashcardReviewLogMapper extends BaseMapper<FlashcardReviewLog> {

    /** 按天分组统计复习次数 */
    @Select("SELECT DATE(created_at) AS day, COUNT(*) AS cnt " +
            "FROM flashcard_review_logs " +
            "WHERE user_id = #{userId} AND created_at >= #{from} " +
            "GROUP BY DATE(created_at)")
    List<Map<String, Object>> countByDay(@Param("userId") Long userId,
                                         @Param("from") LocalDateTime from);

    /** 指定日期范围的按天分组统计 */
    @Select("SELECT DATE(created_at) AS day, COUNT(*) AS cnt " +
            "FROM flashcard_review_logs " +
            "WHERE user_id = #{userId} AND created_at >= #{from} AND created_at < #{to} " +
            "GROUP BY DATE(created_at)")
    List<Map<String, Object>> countByDayRange(@Param("userId") Long userId,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);
}
