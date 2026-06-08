package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.QuizAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface QuizAttemptMapper extends BaseMapper<QuizAttempt> {

    /** 按天分组统计测验次数 */
    @Select("SELECT DATE(created_at) AS day, COUNT(*) AS cnt " +
            "FROM quiz_attempts " +
            "WHERE user_id = #{userId} AND created_at >= #{from} " +
            "GROUP BY DATE(created_at)")
    List<Map<String, Object>> countByDay(@Param("userId") Long userId,
                                         @Param("from") LocalDateTime from);

    /** 指定日期范围的按天分组统计（含答题数+正确数） */
    @Select("SELECT DATE(created_at) AS day, COUNT(*) AS cnt, " +
            "COALESCE(SUM(total_questions), 0) AS total, " +
            "COALESCE(SUM(correct_count), 0) AS correct " +
            "FROM quiz_attempts " +
            "WHERE user_id = #{userId} AND created_at >= #{from} AND created_at < #{to} " +
            "GROUP BY DATE(created_at)")
    List<Map<String, Object>> countByDayRange(@Param("userId") Long userId,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    /** 汇总用户的总答题数和总正确数 */
    @Select("SELECT COALESCE(SUM(total_questions), 0) AS total, " +
            "COALESCE(SUM(correct_count), 0) AS correct " +
            "FROM quiz_attempts WHERE user_id = #{userId}")
    Map<String, Object> sumTotalAndCorrect(@Param("userId") Long userId);

    /** 按天分组汇总测验次数、答题数和正确数 */
    @Select("SELECT DATE(created_at) AS day, COUNT(*) AS cnt, " +
            "COALESCE(SUM(total_questions), 0) AS total, " +
            "COALESCE(SUM(correct_count), 0) AS correct " +
            "FROM quiz_attempts " +
            "WHERE user_id = #{userId} AND created_at >= #{from} " +
            "GROUP BY DATE(created_at)")
    List<Map<String, Object>> sumByDay(@Param("userId") Long userId,
                                       @Param("from") LocalDateTime from);

    /** 按文档分组汇总测验统计 */
    @Select("SELECT doc_id AS docId, " +
            "SUM(total_questions) AS total, " +
            "SUM(correct_count) AS correct " +
            "FROM quiz_attempts " +
            "WHERE user_id = #{userId} AND doc_id IS NOT NULL " +
            "GROUP BY doc_id")
    List<Map<String, Object>> sumByDoc(@Param("userId") Long userId);
}
