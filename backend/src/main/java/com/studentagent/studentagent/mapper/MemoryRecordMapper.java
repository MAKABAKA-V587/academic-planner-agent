package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.MemoryRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface MemoryRecordMapper {

    @Insert("INSERT INTO memory_record (user_id, memory_text, vector_id) VALUES (#{userId}, #{memoryText}, #{vectorId})")
    @Options(useGeneratedKeys = true, keyProperty = "recordId")
    int insert(MemoryRecord record);

    @Insert("<script>" +
            "INSERT INTO memory_record (user_id, memory_text, vector_id) VALUES " +
            "<foreach collection='list' item='r' separator=','>" +
            "(#{r.userId}, #{r.memoryText}, #{r.vectorId})" +
            "</foreach>" +
            "</script>")
    int batchInsert(List<MemoryRecord> records);

    @Select("SELECT * FROM memory_record WHERE user_id = #{userId}")
    List<MemoryRecord> findByUserId(Long userId);

    @Select("SELECT * FROM memory_record WHERE record_id = #{recordId}")
    MemoryRecord findById(Long recordId);

    @Delete("DELETE FROM memory_record WHERE user_id = #{userId}")
    int deleteByUserId(Long userId);

    @Delete("DELETE FROM memory_record WHERE vector_id = #{vectorId}")
    int deleteByVectorId(String vectorId);

    @Delete("<script>" +
            "DELETE FROM memory_record WHERE record_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    int deleteByIds(@Param("ids") List<Long> ids);

    @Update("UPDATE memory_record SET vector_id = #{vectorId} WHERE record_id = #{recordId}")
    int updateVectorId(@Param("recordId") Long recordId, @Param("vectorId") String vectorId);

    @Select("SELECT * FROM memory_record WHERE vector_id IS NULL ORDER BY record_id ASC LIMIT #{limit}")
    List<MemoryRecord> findByNullVectorId(@Param("limit") int limit);

    /** 统计指定用户近N天的记忆增长（按日期分组） */
    @Select("SELECT DATE(create_time) AS date, COUNT(*) AS count FROM memory_record " +
            "WHERE user_id = #{userId} AND create_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY) " +
            "GROUP BY DATE(create_time) ORDER BY date ASC")
    List<Map<String, Object>> countByDay(@Param("userId") Long userId, @Param("days") int days);
}
