package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.UserMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 测试数据辅助接口：将 MySQL 中的记忆同步到 ChromaDB 向量库
 * 仅用于测试数据初始化，生产环境应删除
 */
@Slf4j
@RestController
@RequestMapping("/api/test-data")
@RequiredArgsConstructor
public class TestDataController {

    private final MemoryRecordMapper memoryRecordMapper;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final UserMapper userMapper;

    /**
     * 将指定用户的所有记忆记录从 MySQL 同步到 ChromaDB
     * POST /api/test-data/sync-memories/{userId}
     */
    @PostMapping("/sync-memories/{userId}")
    public Result<Map<String, Object>> syncMemories(@PathVariable Long userId) {
        var user = userMapper.findById(userId);
        if (user == null) {
            return Result.fail(404, "用户不存在");
        }

        List<MemoryRecord> records = memoryRecordMapper.findByUserId(userId);
        if (records.isEmpty()) {
            return Result.ok(Map.of("synced", 0, "message", "该用户没有记忆记录"));
        }

        int synced = 0;
        int skipped = 0;
        for (MemoryRecord record : records) {
            try {
                // 检查 Chroma 中是否已存在（用 vector_id 去重）
                Embedding queryEmbedding = embeddingModel.embed(record.getMemoryText()).content();
                List<EmbeddingMatch<TextSegment>> existing = embeddingStore.search(
                                EmbeddingSearchRequest.builder()
                                        .queryEmbedding(queryEmbedding)
                                        .maxResults(1)
                                        .filter(MetadataFilterBuilder.metadataKey("userId")
                                                .isEqualTo(String.valueOf(userId)))
                                        .build())
                        .matches();

                boolean exists = existing.stream()
                        .anyMatch(m -> record.getVectorId().equals(m.embeddingId()));

                if (!exists) {
                    embeddingStore.addAll(List.of(record.getVectorId()),
                            List.of(queryEmbedding),
                            List.of(TextSegment.from(record.getMemoryText(), Metadata.from(Map.of(
                                    "userId", String.valueOf(userId), "type", "extracted")))));
                    synced++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("同步记忆 {} 到 Chroma 失败: {}", record.getVectorId(), e.getMessage());
            }
        }

        log.info("用户{}记忆同步完成: 新增{}条, 跳过{}条", userId, synced, skipped);
        return Result.ok(Map.of("synced", synced, "skipped", skipped,
                "message", "同步完成：新增" + synced + "条，已存在" + skipped + "条"));
    }

    /**
     * 【临时调试】返回 embeddingModel 对指定文本的实际嵌入向量，并直接在后端内部检索 Chroma
     * POST /api/test-data/debug-embed?text=xxx&userId=11
     */
    @GetMapping("/debug-embed")
    public Result<Map<String, Object>> debugEmbed(@RequestParam String text,
                                                   @RequestParam(required = false) Long userId) {
        dev.langchain4j.data.embedding.Embedding e1 = embeddingModel.embed(text).content();
        var builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(e1)
                .maxResults(10);
        if (userId != null) {
            builder.filter(MetadataFilterBuilder.metadataKey("userId").isEqualTo(String.valueOf(userId)));
        }
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(builder.build()).matches();
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("text", text);
        map.put("dim", e1.dimension());
        map.put("modelClass", embeddingModel.getClass().getName());
        map.put("vector", e1.vectorAsList());
        List<Map<String, Object>> hits = new java.util.ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            Map<String, Object> h = new java.util.LinkedHashMap<>();
            h.put("id", m.embeddingId());
            h.put("score", m.score());
            h.put("text", m.embedded() == null ? "" : m.embedded().text());
            hits.add(h);
        }
        map.put("matches", hits);
        return Result.ok(map);
    }
}
