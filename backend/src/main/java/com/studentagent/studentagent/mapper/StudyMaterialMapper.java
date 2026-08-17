package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.StudyMaterial;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StudyMaterialMapper {

    @Insert("INSERT INTO study_material (user_id, file_name, file_type, file_size, content_text, memory_record_id, is_temp) " +
            "VALUES (#{userId}, #{fileName}, #{fileType}, #{fileSize}, #{contentText}, #{memoryRecordId}, #{isTemp})")
    @Options(useGeneratedKeys = true, keyProperty = "materialId")
    int insert(StudyMaterial material);

    /** 资料库永久文件列表（临时上传不展示） */
    @Select("SELECT * FROM study_material WHERE user_id = #{userId} AND is_temp = 0 ORDER BY create_time DESC")
    List<StudyMaterial> findByUserId(Long userId);

    @Select("SELECT * FROM study_material WHERE material_id = #{materialId}")
    StudyMaterial findById(Long materialId);

    @Update("UPDATE study_material SET memory_record_id = #{memoryRecordId} WHERE material_id = #{materialId}")
    int updateMemoryRecordId(@Param("materialId") Long materialId, @Param("memoryRecordId") Long memoryRecordId);

    @Delete("DELETE FROM study_material WHERE material_id = #{materialId} AND user_id = #{userId}")
    int deleteById(@Param("materialId") Long materialId, @Param("userId") Long userId);
}
