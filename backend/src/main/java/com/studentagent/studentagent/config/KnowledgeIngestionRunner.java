package com.studentagent.studentagent.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时把 knowledge.json 全部嵌入并写入 ChromaDB，
 * 后续 KnowledgeRetrievalTool 直接向量检索，省掉实时 embedding。
 */
@Slf4j
@Component
public class KnowledgeIngestionRunner implements CommandLineRunner {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeIngestionRunner(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge.json");
            Map<String, Map<String, String>> kb = objectMapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<LinkedHashMap<String, Map<String, String>>>() {});

            List<Document> docs = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> subjectEntry : kb.entrySet()) {
                String subject = subjectEntry.getKey();
                for (Map.Entry<String, String> topicEntry : subjectEntry.getValue().entrySet()) {
                    String topic = topicEntry.getKey();
                    String content = topicEntry.getValue();
                    docs.add(new Document(content, Map.of(
                            "type", "knowledge",
                            "subject", subject,
                            "topic", topic
                    )));
                }
            }

            // 先删旧知识库文档（避免每次重启重复累加）
            try {
                List<Document> oldDocs = vectorStore.similaritySearch(
                        org.springframework.ai.vectorstore.SearchRequest.builder()
                                .query("knowledge")
                                .topK(200)
                                .filterExpression("type == 'knowledge'")
                                .build()
                );
                if (!oldDocs.isEmpty()) {
                    List<String> oldIds = oldDocs.stream().map(Document::getId).toList();
                    vectorStore.delete(oldIds);
                    log.info("已删除旧知识库文档{}条", oldIds.size());
                }
            } catch (Exception e) {
                log.debug("删除旧知识库文档时无匹配或失败: {}", e.getMessage());
            }

            vectorStore.add(docs);
            log.info("知识库向量化完成: {} 个科目, {} 条知识点", kb.size(), docs.size());
        } catch (Exception e) {
            log.error("知识库向量化失败，回退到内存模式: {}", e.getMessage());
        }
    }
}
