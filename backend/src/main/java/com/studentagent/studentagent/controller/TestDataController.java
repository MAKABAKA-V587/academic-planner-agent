package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
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
    private final VectorStore vectorStore;
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
                List<Document> existing = vectorStore.similaritySearch(
                        org.springframework.ai.vectorstore.SearchRequest.builder()
                                .query(record.getMemoryText())
                                .topK(1)
                                .filterExpression("userId == '" + userId + "'")
                                .build()
                );

                boolean exists = existing.stream()
                        .anyMatch(d -> record.getVectorId().equals(d.getId()));

                if (!exists) {
                    Document doc = Document.builder()
                            .id(record.getVectorId())
                            .text(record.getMemoryText())
                            .metadata(Map.of("userId", String.valueOf(userId), "type", "extracted"))
                            .build();
                    vectorStore.add(List.of(doc));
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
}
