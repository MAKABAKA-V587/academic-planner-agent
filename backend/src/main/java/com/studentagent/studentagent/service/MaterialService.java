package com.studentagent.studentagent.service;

import com.studentagent.studentagent.entity.StudyMaterial;
import com.studentagent.studentagent.mapper.StudyMaterialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资料库服务：管理学习资料原件（词表/笔记等）。
 * 资料库文件可随时查看/下载，并通过「选择资料」挂到会话供 AI 参考；
 * 聊天页上传的临时文件只挂当前会话，移除即删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialService {

    private final StudyMaterialMapper studyMaterialMapper;
    private final ChatService chatService;

    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2MB

    /**
     * 上传资料：校验文本类型 → 存储。
     * temp=true 为临时上传（仅挂当前会话，不进资料库、不写全局记忆副本）；
     * temp=false 为资料库永久文件。
     */
    public Map<String, Object> upload(Long userId, MultipartFile file, boolean temp) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件为空");
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) filename = "unnamed.txt";
        String lower = filename.toLowerCase();
        String type = "txt";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) type = "md";
        else if (lower.endsWith(".csv")) type = "csv";
        else if (!lower.endsWith(".txt")) throw new IllegalArgumentException("仅支持 .txt / .md / .csv 文本文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("文件不能超过 2MB");

        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalArgumentException("文件读取失败: " + e.getMessage());
        }
        if (content.isEmpty()) throw new IllegalArgumentException("文件内容为空");

        StudyMaterial material = new StudyMaterial();
        material.setUserId(userId);
        material.setFileName(filename);
        material.setFileType(type);
        material.setFileSize(file.getSize());
        material.setContentText(content);
        material.setIsTemp(temp ? 1 : 0);
        studyMaterialMapper.insert(material);

        log.info("用户{}上传{}资料: {} ({}字节)", userId, temp ? "临时" : "资料库", filename, file.getSize());
        return Map.of("materialId", material.getMaterialId(), "fileName", filename);
    }

    /**
     * 资料库列表（最新在前），只含元信息不含正文
     */
    public List<Map<String, Object>> list(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StudyMaterial m : studyMaterialMapper.findByUserId(userId)) {
            out.add(Map.of(
                    "materialId", m.getMaterialId(),
                    "fileName", m.getFileName(),
                    "fileType", m.getFileType(),
                    "fileSize", m.getFileSize(),
                    "chars", m.getContentText() != null ? m.getContentText().length() : 0,
                    "createTime", m.getCreateTime() != null ? m.getCreateTime().toString() : ""
            ));
        }
        return out;
    }

    /**
     * 查看资料详情（原文），仅限本人
     */
    public Map<String, Object> detail(Long userId, Long materialId) {
        StudyMaterial m = studyMaterialMapper.findById(materialId);
        if (m == null || !m.getUserId().equals(userId)) {
            throw new IllegalArgumentException("资料不存在");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("materialId", m.getMaterialId());
        map.put("fileName", m.getFileName());
        map.put("fileType", m.getFileType());
        map.put("content", m.getContentText());
        return map;
    }

    /**
     * 删除资料：删资料库原件 + 同步删记忆副本
     */
    public int delete(Long userId, Long materialId) {
        StudyMaterial m = studyMaterialMapper.findById(materialId);
        if (m == null || !m.getUserId().equals(userId)) return 0;
        int n = studyMaterialMapper.deleteById(materialId, userId);
        if (n > 0 && m.getMemoryRecordId() != null) {
            try {
                chatService.deleteUploadedFile(userId, m.getMemoryRecordId());
            } catch (Exception e) {
                log.warn("删除资料{}的AI副本失败: {}", materialId, e.getMessage());
            }
        }
        return n;
    }
}
