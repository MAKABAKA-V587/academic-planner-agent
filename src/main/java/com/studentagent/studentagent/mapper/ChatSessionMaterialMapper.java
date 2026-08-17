package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.ChatSessionMaterial;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatSessionMaterialMapper {

    @Insert("INSERT IGNORE INTO chat_session_material (session_id, material_id) VALUES (#{sessionId}, #{materialId})")
    int insert(ChatSessionMaterial m);

    @Select("SELECT * FROM chat_session_material WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<ChatSessionMaterial> findBySessionId(Long sessionId);

    @Delete("DELETE FROM chat_session_material WHERE session_id = #{sessionId} AND material_id = #{materialId}")
    int delete(@Param("sessionId") Long sessionId, @Param("materialId") Long materialId);
}
