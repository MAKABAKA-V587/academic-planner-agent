package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.ChatMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface MessageMapper {

    @Insert("INSERT INTO chat_message (session_id, role, content) VALUES (#{sessionId}, #{role}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "messageId")
    int insert(ChatMessage message);

    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY create_time ASC")
    List<ChatMessage> findBySessionId(Long sessionId);

    /** 查询单条消息（用于编辑/删除校验） */
    @Select("SELECT * FROM chat_message WHERE message_id = #{messageId}")
    ChatMessage findById(@Param("messageId") Long messageId);

    /** 更新单条消息内容 */
    @Update("UPDATE chat_message SET content = #{content} WHERE message_id = #{messageId}")
    int updateContent(@Param("messageId") Long messageId, @Param("content") String content);

    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(Long sessionId);

    @Delete("DELETE FROM chat_message WHERE message_id IN (" +
            "SELECT t.message_id FROM (" +
            "SELECT message_id FROM chat_message WHERE session_id = #{sessionId} ORDER BY message_id DESC LIMIT #{count}" +
            ") t)")
    int deleteLastN(@Param("sessionId") Long sessionId, @Param("count") int count);

    @Delete("DELETE FROM chat_message WHERE message_id = (" +
            "SELECT t.message_id FROM (" +
            "SELECT message_id FROM chat_message WHERE session_id = #{sessionId} AND role = 'assistant' ORDER BY message_id DESC LIMIT 1" +
            ") t)")
    int deleteLastAssistant(@Param("sessionId") Long sessionId);

    /** 获取会话最后一条 user 消息的 message_id，没有则返回 null */
    @Select("SELECT MAX(message_id) FROM chat_message WHERE session_id = #{sessionId} AND role = 'user'")
    Long getLastUserMessageId(@Param("sessionId") Long sessionId);

    /** 删除指定 message_id 及之后的所有消息（删除一整轮：user + tool 消息 + assistant） */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId} AND message_id >= #{messageId}")
    int deleteFrom(@Param("sessionId") Long sessionId, @Param("messageId") Long messageId);

    /** 删除 lastUserMessageId 之后的所有消息（不含 user，只删 tool_call/tool_result/assistant） */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId} AND message_id > #{messageId}")
    int deleteAfter(@Param("sessionId") Long sessionId, @Param("messageId") Long messageId);

    /** 查询用户最近N条消息（跨全部会话），用于手动记忆提取 */
    @Select("SELECT cm.* FROM chat_message cm " +
            "INNER JOIN chat_session cs ON cm.session_id = cs.session_id " +
            "WHERE cs.user_id = #{userId} " +
            "ORDER BY cm.create_time DESC LIMIT #{limit}")
    List<ChatMessage> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /** 统计指定用户近N天的用户消息数（按日期分组），用于活跃趋势图 */
    @Select("SELECT DATE(cm.create_time) AS date, COUNT(*) AS count FROM chat_message cm " +
            "INNER JOIN chat_session cs ON cm.session_id = cs.session_id " +
            "WHERE cs.user_id = #{userId} AND cm.role = 'user' " +
            "AND cm.create_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY) " +
            "GROUP BY DATE(cm.create_time) ORDER BY date ASC")
    List<Map<String, Object>> countByDay(@Param("userId") Long userId, @Param("days") int days);

    /** 查询用户全部 user 消息内容，用于科目关键词扫描 */
    @Select("SELECT cm.content FROM chat_message cm " +
            "INNER JOIN chat_session cs ON cm.session_id = cs.session_id " +
            "WHERE cs.user_id = #{userId} AND cm.role = 'user' " +
            "ORDER BY cm.create_time DESC")
    List<String> findAllUserMessages(@Param("userId") Long userId);

    /** 当前窗口（最近 rounds 轮）第一条 user 消息的 message_id；不足 rounds 轮返回 null（方案A水位线基准） */
    @Select("SELECT MIN(t.message_id) FROM (" +
            "SELECT message_id FROM chat_message " +
            "WHERE session_id = #{sessionId} AND role = 'user' " +
            "ORDER BY message_id DESC LIMIT #{rounds}) t")
    Long getWindowStartMessageId(@Param("sessionId") Long sessionId, @Param("rounds") int rounds);

    /** 统计窗口起点之前、水位线之后滑出窗口的 user 消息轮数（方案A去抖触发） */
    @Select("SELECT COUNT(*) FROM chat_message " +
            "WHERE session_id = #{sessionId} AND role = 'user' " +
            "AND message_id > #{afterId} AND message_id < #{beforeId}")
    int countUserMessagesBetween(@Param("sessionId") Long sessionId,
                                 @Param("afterId") long afterId,
                                 @Param("beforeId") long beforeId);

    /** 取滑出窗口段的 user/assistant 原文（按 message_id 正序，限量），供滚动摘要渲染 */
    @Select("SELECT * FROM chat_message " +
            "WHERE session_id = #{sessionId} AND role IN ('user','assistant') " +
            "AND message_id > #{afterId} AND message_id < #{beforeId} " +
            "ORDER BY message_id ASC LIMIT #{limit}")
    List<ChatMessage> findSummarizableBetween(@Param("sessionId") Long sessionId,
                                              @Param("afterId") long afterId,
                                              @Param("beforeId") long beforeId,
                                              @Param("limit") int limit);
}
