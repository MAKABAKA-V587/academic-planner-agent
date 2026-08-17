package com.studentagent.studentagent.mapper;

import com.studentagent.studentagent.entity.StudentProfile;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ProfileMapper {

    @Select("SELECT * FROM student_profile WHERE user_id = #{userId}")
    StudentProfile findByUserId(Long userId);

    @Insert("INSERT INTO student_profile (user_id, weak_subjects, exam_plans, study_goals) " +
            "VALUES (#{userId}, #{weakSubjects}, #{examPlans}, #{studyGoals})")
    @Options(useGeneratedKeys = true, keyProperty = "profileId")
    int insert(StudentProfile profile);

    @Update("UPDATE student_profile SET weak_subjects = #{weakSubjects}, " +
            "exam_plans = #{examPlans}, study_goals = #{studyGoals}, " +
            "update_time = NOW() " +
            "WHERE user_id = #{userId}")
    int updateByUserId(StudentProfile profile);
}
