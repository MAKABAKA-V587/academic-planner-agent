package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.WeeklyReport;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WeeklyReportMapper {

    @Select("SELECT * FROM weekly_report WHERE user_id = #{userId} AND week_start = #{weekStart}")
    WeeklyReport findByUserAndWeek(@Param("userId") Long userId, @Param("weekStart") String weekStart);

    @Insert("INSERT INTO weekly_report (user_id, week_start, week_end, content) " +
            "VALUES (#{userId}, #{weekStart}, #{weekEnd}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "reportId")
    int insert(WeeklyReport report);

    @Update("UPDATE weekly_report SET content = #{content}, create_time = NOW() " +
            "WHERE report_id = #{reportId}")
    int updateContent(@Param("reportId") Long reportId, @Param("content") String content);

    @Select("SELECT * FROM weekly_report WHERE user_id = #{userId} ORDER BY create_time DESC")
    java.util.List<WeeklyReport> findByUserId(Long userId);
}
