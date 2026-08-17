package com.studentagent.studentagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资料库表 study_material
 */
@Data
public class StudyMaterial {
    private Long materialId;
    private Long userId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String contentText;
    private Long memoryRecordId;
    private Integer isTemp; // 1=临时上传（只挂当前会话），0=资料库永久文件
    private LocalDateTime createTime;
}
