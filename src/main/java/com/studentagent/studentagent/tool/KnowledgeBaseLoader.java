package com.studentagent.studentagent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识点加载器 —— 启动时从 knowledge.json 读取全部考点到内存，
 * 后续新增/修改知识点只需编辑 JSON 文件，无需改 Java 代码。
 */
@Slf4j
@Component
public class KnowledgeBaseLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 科目 → (关键词 → 内容)，与原来 KNOWLEDGE_BASE 结构一致 */
    @Getter
    private Map<String, Map<String, String>> knowledgeBase = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge.json");
            knowledgeBase = objectMapper.readValue(resource.getInputStream(),
                    new TypeReference<LinkedHashMap<String, Map<String, String>>>() {});
            int total = knowledgeBase.values().stream().mapToInt(Map::size).sum();
            log.info("知识库加载完成: {} 个科目, {} 条知识点", knowledgeBase.size(), total);
        } catch (Exception e) {
            log.error("加载 knowledge.json 失败，知识库为空: {}", e.getMessage());
            knowledgeBase = new LinkedHashMap<>();
        }
    }
}
