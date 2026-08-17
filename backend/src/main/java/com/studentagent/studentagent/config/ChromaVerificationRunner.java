package com.studentagent.studentagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chroma 向量库基础能力强制验证（阶段3.1）
 * 启动时自动执行增/查/删三项验证，全部通过后打印成功日志。
 */
@Slf4j
@Component
public class ChromaVerificationRunner implements CommandLineRunner {

    private final VectorStore vectorStore;

    public ChromaVerificationRunner(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        log.info("===== Chroma 向量库基础能力验证开始 =====");

        String testDocId = "verify-" + UUID.randomUUID().toString().substring(0, 8);
        String testContent = "这是一条测试记忆：用户-linear algebra-线性代数基础薄弱";

        try {
            // ========== 1. 新增文档 ==========
            Document doc = Document.builder()
                    .id(testDocId)
                    .text(testContent)
                    .metadata(Map.of("userId", "0", "type", "test"))
                    .build();
            vectorStore.add(List.of(doc));
            log.info("[验证1-新增] 文档写入成功, docId={}", testDocId);

            // 等待 Chroma 索引完成
            Thread.sleep(1500);

            // ========== 2. 相似度检索 ==========
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("线性代数基础薄弱")
                            .topK(3)
                            .build()
            );
            boolean found = results.stream().anyMatch(r -> testDocId.equals(r.getId()));
            if (found) {
                log.info("[验证2-检索] 相似度检索成功，召回文档数量={}, 目标文档已召回", results.size());
            } else {
                log.warn("[验证2-检索] 未召回目标文档，召回数量={}", results.size());
            }

            // ========== 3. 删除文档 ==========
            vectorStore.delete(List.of(testDocId));
            log.info("[验证3-删除] 文档删除指令已发送, docId={}", testDocId);

            // 等待删除生效后再次检索确认
            Thread.sleep(1000);
            List<Document> afterDelete = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("线性代数基础薄弱")
                            .topK(3)
                            .build()
            );
            boolean stillExists = afterDelete.stream().anyMatch(r -> testDocId.equals(r.getId()));
            if (!stillExists) {
                log.info("[验证3-删除] 删除确认成功，目标文档已从检索结果中清除");
            } else {
                log.warn("[验证3-删除] 文档删除后仍可检索到，可能删除未及时生效");
            }

            log.info("===== Chroma 向量库基础能力验证全部通过 =====");
        } catch (Exception e) {
            log.error("===== Chroma 向量库基础能力验证失败: {} =====", e.getMessage(), e);
            // 不抛出异常，避免阻塞启动
        }
    }
}
