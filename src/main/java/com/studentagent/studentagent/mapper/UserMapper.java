package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.SysUser;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    SysUser findByUsername(String username);

    @Select("SELECT * FROM sys_user WHERE user_id = #{userId}")
    SysUser findById(Long userId);

    @Insert("INSERT INTO sys_user (username, password, name, major, grade) " +
            "VALUES (#{username}, #{password}, #{name}, #{major}, #{grade})")
    @Options(useGeneratedKeys = true, keyProperty = "userId")
    int insert(SysUser user);

    @Update("UPDATE sys_user SET user_tags = #{userTags} WHERE user_id = #{userId}")
    int updateTags(@Param("userId") Long userId, @Param("userTags") String userTags);

    @Update("UPDATE sys_user SET name = #{name} WHERE user_id = #{userId}")
    int updateName(@Param("userId") Long userId, @Param("name") String name);

    @Update("UPDATE sys_user SET password = #{password} WHERE user_id = #{userId}")
    int updatePassword(@Param("userId") Long userId, @Param("password") String password);
}
