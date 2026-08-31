package com.studentagent.studentagent.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
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

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KnowledgeIngestionRunner(EmbeddingStore<TextSegment> embeddingStore,
                                    EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(String... args) {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge.json");
            Map<String, Map<String, String>> kb = objectMapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<LinkedHashMap<String, Map<String, String>>>() {});

            List<String> ids = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> subjectEntry : kb.entrySet()) {
                String subject = subjectEntry.getKey();
                for (Map.Entry<String, String> topicEntry : subjectEntry.getValue().entrySet()) {
                    String topic = topicEntry.getKey();
                    String content = topicEntry.getValue();
                    // 用 科目|主题 作稳定 id，重复重启不会累加
                    ids.add(subject + "|" + topic);
                    texts.add(content);
                    segments.add(TextSegment.from(content, Metadata.from(Map.of(
                            "type", "knowledge",
                            "subject", subject,
                            "topic", topic
                    ))));
                }
            }

            // 先删旧知识库文档（避免每次重启重复累加）
            try {
                Embedding queryEmbedding = embeddingModel.embed("knowledge").content();
                List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                                EmbeddingSearchRequest.builder()
                                        .queryEmbedding(queryEmbedding)
                                        .maxResults(200)
                                        .filter(MetadataFilterBuilder.metadataKey("type").isEqualTo("knowledge"))
                                        .build())
                        .matches();
                if (!matches.isEmpty()) {
                    List<String> oldIds = matches.stream()
                            .map(EmbeddingMatch::embeddingId)
                            .filter(java.util.Objects::nonNull)
                            .toList();
                    embeddingStore.removeAll(oldIds);
                    log.info("已删除旧知识库文档{}条", oldIds.size());
                }
            } catch (Exception e) {
                log.debug("删除旧知识库文档时无匹配或失败: {}", e.getMessage());
            }

            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(ids, embeddings, segments);
            log.info("知识库向量化完成: {} 个科目, {} 条知识点", kb.size(), texts.size());
        } catch (Exception e) {
            log.error("知识库向量化失败，回退到内存模式: {}", e.getMessage());
        }
    }
}
