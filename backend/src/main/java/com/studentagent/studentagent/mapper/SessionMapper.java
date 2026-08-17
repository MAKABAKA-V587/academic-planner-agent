package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.ChatSession;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SessionMapper {

    @Insert("INSERT INTO chat_session (user_id, title) VALUES (#{userId}, #{title})")
    @Options(useGeneratedKeys = true, keyProperty = "sessionId")
    int insert(ChatSession session);

    @Select("SELECT * FROM chat_session WHERE user_id = #{userId} ORDER BY last_active_time DESC")
    List<ChatSession> findByUserId(Long userId);

    @Select("SELECT * FROM chat_session WHERE session_id = #{sessionId}")
    ChatSession findById(Long sessionId);

    @Delete("DELETE FROM chat_session WHERE session_id = #{sessionId}")
    int deleteById(Long sessionId);

    @Update("UPDATE chat_session SET last_active_time = NOW() WHERE session_id = #{sessionId}")
    int touch(Long sessionId);

    @Update("UPDATE chat_session SET title = #{title}, title_locked = 1 WHERE session_id = #{sessionId}")
    int renameAndLock(Long sessionId, String title);

    @Update("UPDATE chat_session SET title = #{title}, last_active_time = NOW() WHERE session_id = #{sessionId}")
    int autoTitle(Long sessionId, String title);
}
