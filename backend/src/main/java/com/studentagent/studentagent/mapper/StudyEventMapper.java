package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.StudyEvent;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface StudyEventMapper {

    @Insert("INSERT INTO study_event (user_id, title, event_date, end_date, description, event_type, source, color) " +
            "VALUES (#{userId}, #{title}, #{eventDate}, #{endDate}, #{description}, #{eventType}, #{source}, #{color})")
    @Options(useGeneratedKeys = true, keyProperty = "eventId")
    int insert(StudyEvent event);

    @Select("SELECT * FROM study_event WHERE user_id = #{userId} " +
            "AND event_date < #{nextMonthStart} " +
            "AND (end_date IS NULL OR end_date >= #{monthStart}) " +
            "ORDER BY event_date ASC")
    List<StudyEvent> findByUserAndMonth(@Param("userId") Long userId,
                                         @Param("monthStart") LocalDate monthStart,
                                         @Param("nextMonthStart") LocalDate nextMonthStart);

    @Select("SELECT * FROM study_event WHERE event_id = #{eventId}")
    StudyEvent findById(Long eventId);

    @Update("UPDATE study_event SET title=#{title}, event_date=#{eventDate}, end_date=#{endDate}, " +
            "description=#{description}, event_type=#{eventType}, color=#{color} WHERE event_id=#{eventId}")
    int update(StudyEvent event);

    @Delete("DELETE FROM study_event WHERE event_id = #{eventId}")
    int deleteById(Long eventId);

    /** 删除用户所有事件 */
    @Delete("DELETE FROM study_event WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    /** 按日期范围查询 */
    @Select("SELECT * FROM study_event WHERE user_id = #{userId} " +
            "AND event_date <= #{endDate} " +
            "AND (end_date IS NULL OR end_date >= #{startDate}) " +
            "ORDER BY event_date ASC")
    List<StudyEvent> findByDateRange(@Param("userId") Long userId,
                                     @Param("startDate") LocalDate startDate,
                                     @Param("endDate") LocalDate endDate);

    /** 按标题模糊匹配 + 日期删除 */
    @Delete("DELETE FROM study_event WHERE user_id = #{userId} " +
            "AND title LIKE CONCAT('%', #{title}, '%') " +
            "AND event_date = #{date}")
    int deleteByTitleAndDate(@Param("userId") Long userId,
                             @Param("title") String title,
                             @Param("date") LocalDate date);

    /** 按标题模糊匹配删除（不限日期） */
    @Delete("DELETE FROM study_event WHERE user_id = #{userId} " +
            "AND title LIKE CONCAT('%', #{title}, '%')")
    int deleteByTitle(@Param("userId") Long userId, @Param("title") String title);

    /** 删除指定标题在日期范围内的旧 AI 导入事件（重新导入同一计划时清理旧版，不误删其他日期/其他主题的同名任务） */
    @Delete("DELETE FROM study_event WHERE user_id = #{userId} AND source = 'ai' AND title = #{title} " +
            "AND event_date >= #{start} AND event_date <= #{end}")
    int deleteByTitleInRange(@Param("userId") Long userId, @Param("title") String title,
                             @Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 删除今日所有事件：单日事件(end_date为空)仅当 event_date=今天才删；跨日事件覆盖今天才删 */
    @Delete("DELETE FROM study_event WHERE user_id = #{userId} AND ( " +
            "(end_date IS NULL AND event_date = #{today}) " +
            "OR (end_date IS NOT NULL AND event_date <= #{today} AND end_date >= #{today}) )")
    int deleteTodayEvents(@Param("userId") Long userId, @Param("today") LocalDate today);

    /** 检查是否存在相同的事件（按标题+日期范围去重） */
    @Select("SELECT COUNT(*) FROM study_event WHERE user_id = #{userId} AND title = #{title} " +
            "AND event_date = #{eventDate} AND (end_date = #{endDate} OR (end_date IS NULL AND #{endDate} IS NULL))")
    int countDuplicate(@Param("userId") Long userId, @Param("title") String title,
                       @Param("eventDate") LocalDate eventDate, @Param("endDate") LocalDate endDate);

    /** 查询今日任务：单日事件(end_date为空)仅当 event_date=今天；跨日事件覆盖今天 */
    @Select("SELECT * FROM study_event WHERE user_id = #{userId} AND ( " +
            "(end_date IS NULL AND event_date = #{today}) " +
            "OR (end_date IS NOT NULL AND event_date <= #{today} AND end_date >= #{today}) ) " +
            "ORDER BY event_date ASC")
    List<StudyEvent> findTodayByUserId(@Param("userId") Long userId, @Param("today") LocalDate today);

    /** 更新任务完成状态 */
    @Update("UPDATE study_event SET completed = #{completed} WHERE event_id = #{eventId}")
    int updateCompleted(@Param("eventId") Long eventId, @Param("completed") Boolean completed);

    /** 更新跨天任务的按天打卡记录（逗号分隔日期串，null=清空全部打卡） */
    @Update("UPDATE study_event SET completed_dates = #{completedDates} WHERE event_id = #{eventId}")
    int updateCompletedDates(@Param("eventId") Long eventId, @Param("completedDates") String completedDates);

    /** 查询用户最近完成的 N 个任务（事件级：completed=1 含旧数据整条完成的跨天任务，或跨天有任一打卡） */
    @Select("SELECT * FROM study_event WHERE user_id = #{userId} AND " +
            "(completed = 1 OR (end_date > event_date AND completed_dates IS NOT NULL AND completed_dates != '')) " +
            "ORDER BY event_date DESC, create_time DESC LIMIT #{limit}")
    List<StudyEvent> findCompletedByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /** 累计完成的任务数（单日任务部分；跨天任务按 completed_dates 打卡口径由服务层展开计次） */
    @Select("SELECT COUNT(*) FROM study_event WHERE user_id = #{userId} AND completed = 1 " +
            "AND (end_date IS NULL OR end_date = event_date)")
    int countCompleted(@Param("userId") Long userId);

    /** 某个日期以来完成的任务数（如近7天，单日任务部分） */
    @Select("SELECT COUNT(*) FROM study_event WHERE user_id = #{userId} AND completed = 1 " +
            "AND (end_date IS NULL OR end_date = event_date) AND event_date >= #{since}")
    int countCompletedSince(@Param("userId") Long userId, @Param("since") LocalDate since);

    /** 未完成任务数（单日任务部分；跨天任务未完成数由服务层按 findSpanEvents 分类得出） */
    @Select("SELECT COUNT(*) FROM study_event WHERE user_id = #{userId} " +
            "AND (end_date IS NULL OR end_date = event_date) AND (completed IS NULL OR completed = 0)")
    int countPending(@Param("userId") Long userId);

    /** 跨天任务全部明细（completed + completed_dates），由服务层按口径分类统计 */
    @Select("SELECT event_id, event_date, end_date, completed, completed_dates FROM study_event " +
            "WHERE user_id = #{userId} AND end_date > event_date")
    List<StudyEvent> findSpanEvents(@Param("userId") Long userId);

    /** 近N天每日完成任务数（单日任务部分，按 event_date 分组），用于学习进度看板柱状图 */
    @Select("SELECT event_date AS date, COUNT(*) AS count FROM study_event " +
            "WHERE user_id = #{userId} AND completed = 1 " +
            "AND (end_date IS NULL OR end_date = event_date) AND event_date >= #{since} " +
            "GROUP BY event_date ORDER BY date ASC")
    List<Map<String, Object>> countCompletedByDay(@Param("userId") Long userId, @Param("since") LocalDate since);

    /** 查询单日任务所有完成日期（去重、倒序）；跨天任务的打卡日期由服务层合并，用于连续学习天数 */
    @Select("SELECT DISTINCT event_date FROM study_event WHERE user_id = #{userId} AND completed = 1 " +
            "AND (end_date IS NULL OR end_date = event_date) ORDER BY event_date DESC")
    List<LocalDate> findCompletedDates(@Param("userId") Long userId);
}
