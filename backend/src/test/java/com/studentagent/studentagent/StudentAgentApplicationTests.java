package com.studentagent.studentagent;

import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.service.chat.ChatHistoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试（Testcontainers）：真实 MySQL + Redis + Chroma 容器内加载完整 Spring 上下文，
 * 覆盖 Mapper 落库回环与对话历史缓存回环。CI（ubuntu-latest 自带 Docker）与本地均可运行。
 * LLM/搜索 API 用占位 key（Bean 构造不外呼；两个 CommandLineRunner 自带异常兜底不阻塞启动）；
 * Chroma 为真实容器（embeddingStore Bean 构造时即建集合，无法用占位服务替代）。
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class StudentAgentApplicationTests {

    static {
        // 与 StudentAgentApplication.main 保持一致：classpath 上有两个 langchain4j HTTP client
        // （starter 的 spring-restclient + chroma 的 jdk client）时必须显式指定工厂，
        // 否则上下文加载失败；统一走 jdk client（与 Chroma 0.4.24 兼容）
        System.setProperty("langchain4j.http.clientBuilderFactory",
                "dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory");
    }

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // 最小建表脚本（本地 MySQL 的真实 schema 由外部维护，容器内从零初始化）
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:sql/test-schema.sql");
        // 占位 key：上下文构造不外呼；runner 失败自带兜底
        registry.add("langchain4j.open-ai.chat-model.api-key", () -> "test-key");
        registry.add("langchain4j.open-ai.streaming-chat-model.api-key", () -> "test-key");
        registry.add("langchain4j.open-ai.embedding-model.api-key", () -> "test-key");
        registry.add("tavily.api-key", () -> "test-key");
    }

    @Autowired
    private MemoryRecordMapper memoryRecordMapper;

    @Autowired
    private ChatHistoryStore chatHistoryStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("完整 Spring 上下文在真实 MySQL/Redis 容器中加载成功")
    void contextLoads() {
        assertNotNull(memoryRecordMapper);
        assertNotNull(chatHistoryStore);
    }

    @Test
    @DisplayName("memory_record 落库回环：批量插入 → 查无向量 → 回填 → 查空 → 清理")
    void memoryRecordMapperRoundtrip() {
        Long userId = 99001L;
        try {
            MemoryRecord r1 = newRecord(userId, "薄弱科目-高等数学-多元微积分薄弱");
            MemoryRecord r2 = newRecord(userId, "学习目标-考研-目标985院校");
            MemoryRecord r3 = newRecord(userId, "学习习惯-作息-早起背单词");
            memoryRecordMapper.batchInsert(List.of(r1, r2, r3));

            // 三条均为 vector_id IS NULL，补偿任务扫描可见
            List<MemoryRecord> pendings = memoryRecordMapper.findByNullVectorId(50);
            assertTrue(pendings.size() >= 3, "插入的三条记录应出现在待补列表中");
            assertTrue(pendings.stream().allMatch(r -> r.getVectorId() == null));

            // 模拟补偿任务回填
            for (MemoryRecord r : pendings) {
                memoryRecordMapper.updateVectorId(r.getRecordId(), "vec_" + r.getRecordId());
            }
            assertTrue(memoryRecordMapper.findByNullVectorId(50).stream()
                    .noneMatch(r -> r.getUserId().equals(userId)), "回填后不应再被扫描到");

            // 按 user 查询与删除
            List<MemoryRecord> all = memoryRecordMapper.findByUserId(userId);
            assertEquals(3, all.size());
        } finally {
            memoryRecordMapper.deleteByUserId(userId);
        }
        assertEquals(0, memoryRecordMapper.findByUserId(userId).size(), "清理后应为空");
    }

    @Test
    @DisplayName("对话历史缓存回环：Redis 写入 → 读取一致 → 清除后降级 MySQL 返回空")
    void chatHistoryStoreRoundtrip() {
        Long sessionId = 42001L;
        try {
            List<Map<String, String>> history = List.of(
                    Map.of("role", "user", "content", "帮我生成考研数学复习计划"),
                    Map.of("role", "assistant", "content", "好的，基础阶段先过教材……"));
            chatHistoryStore.saveHistory(sessionId, history);

            assertEquals(history, chatHistoryStore.loadHistory(sessionId), "读回应与写入一致");
            assertTrue(redisTemplate.getExpire(ChatHistoryStore.HISTORY_KEY_PREFIX + sessionId) > 0,
                    "历史缓存应有 TTL");

            chatHistoryStore.clearHistory(sessionId);
            assertTrue(chatHistoryStore.loadHistory(sessionId).isEmpty(),
                    "清除后应降级 MySQL（空会话 → 空历史）");
        } finally {
            chatHistoryStore.clearHistory(sessionId);
        }
        assertNull(redisTemplate.opsForValue().get(ChatHistoryStore.HISTORY_KEY_PREFIX + sessionId));
    }

    private static MemoryRecord newRecord(Long userId, String text) {
        MemoryRecord r = new MemoryRecord();
        r.setUserId(userId);
        r.setMemoryText(text);
        r.setCreateTime(LocalDateTime.now());
        return r;
    }
}
