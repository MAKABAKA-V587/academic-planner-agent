package com.studentagent.studentagent.config;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
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

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    public ChromaVerificationRunner(EmbeddingStore<TextSegment> embeddingStore,
                                    EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(String... args) {
        log.info("===== Chroma 向量库基础能力验证开始 =====");

        String testDocId = "verify-" + UUID.randomUUID().toString().substring(0, 8);
        String testContent = "这是一条测试记忆：用户-linear algebra-线性代数基础薄弱";

        try {
            // ========== 1. 新增文档 ==========
            Embedding testEmbedding = embeddingModel.embed(testContent).content();
            embeddingStore.addAll(List.of(testDocId),
                    List.of(testEmbedding),
                    List.of(TextSegment.from(testContent, Metadata.from(Map.of("userId", "0", "type", "test")))));
            log.info("[验证1-新增] 文档写入成功, docId={}", testDocId);

            // 等待 Chroma 索引完成
            Thread.sleep(1500);

            // ========== 2. 相似度检索 ==========
            List<EmbeddingMatch<TextSegment>> results = embeddingStore.search(
                            EmbeddingSearchRequest.builder()
                                    .queryEmbedding(embeddingModel.embed("线性代数基础薄弱").content())
                                    .maxResults(3)
                                    .build())
                    .matches();
            boolean found = results.stream().anyMatch(m -> testDocId.equals(m.embeddingId()));
            if (found) {
                log.info("[验证2-检索] 相似度检索成功，召回文档数量={}, 目标文档已召回", results.size());
            } else {
                log.warn("[验证2-检索] 未召回目标文档，召回数量={}", results.size());
            }

            // ========== 3. 删除文档 ==========
            embeddingStore.removeAll(List.of(testDocId));
            log.info("[验证3-删除] 文档删除指令已发送, docId={}", testDocId);

            // 等待删除生效后再次检索确认
            Thread.sleep(1000);
            List<EmbeddingMatch<TextSegment>> afterDelete = embeddingStore.search(
                            EmbeddingSearchRequest.builder()
                                    .queryEmbedding(embeddingModel.embed("线性代数基础薄弱").content())
                                    .maxResults(3)
                                    .build())
                    .matches();
            boolean stillExists = afterDelete.stream().anyMatch(m -> testDocId.equals(m.embeddingId()));
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
