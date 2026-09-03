package com.studentagent.studentagent;

import com.studentagent.studentagent.service.chat.TokenBudgetService;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenBudgetService 单测（成本控制核心逻辑，全部 mock Redis，不依赖容器）：
 * - 限额开关 / 限额判断（超限、未超限、Redis 故障放行）
 * - 记账累加与 TTL 续期 / 记账异常吞掉不阻断
 * - Usage.fromChat 对 null 与 null 计数的容错
 */
class TokenBudgetServiceTest {

    private TokenBudgetService service;
    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOps = Mockito.mock(HashOperations.class);

    private static final String KEY = "token:usage:1:" + java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        service = new TokenBudgetService(redisTemplate);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "dailyLimit", 1000L);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    @Test
    @DisplayName("开关关闭：不记账也不拦截")
    void disabledSkipsEverything() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertFalse(service.exceeded(1L));
        assertEquals(-1, service.getTodayUsage(1L));
        service.recordUsage(1L, new TokenBudgetService.Usage(100, 50));
        verify(hashOps, never()).increment(anyString(), anyString(), eq(100L));
    }

    @Test
    @DisplayName("超限：今日用量达到限额 → exceeded=true")
    void exceededWhenUsageReachesLimit() {
        when(hashOps.get(KEY, "in")).thenReturn("600");
        when(hashOps.get(KEY, "out")).thenReturn("400");
        assertTrue(service.exceeded(1L));
    }

    @Test
    @DisplayName("未超限：今日用量低于限额 → exceeded=false")
    void notExceededBelowLimit() {
        when(hashOps.get(KEY, "in")).thenReturn("300");
        when(hashOps.get(KEY, "out")).thenReturn("100");
        assertFalse(service.exceeded(1L));
        assertEquals(400, service.getTodayUsage(1L));
    }

    @Test
    @DisplayName("Redis 故障：查询返回 -1 且视为未超限（成本控制绝不阻断主链路）")
    void redisFailureDoesNotBlock() {
        when(hashOps.get(anyString(), anyString())).thenThrow(new RuntimeException("connection refused"));
        assertEquals(-1, service.getTodayUsage(1L));
        assertFalse(service.exceeded(1L));
    }

    @Test
    @DisplayName("记账：输入/输出分别累加并续期 TTL")
    void recordUsageIncrementsBothFields() {
        service.recordUsage(1L, new TokenBudgetService.Usage(120, 30));
        verify(hashOps).increment(KEY, "in", 120L);
        verify(hashOps).increment(KEY, "out", 30L);
        verify(redisTemplate).expire(eq(KEY), Mockito.anyLong(), Mockito.any());
    }

    @Test
    @DisplayName("记账异常：吞掉不抛出（不影响对话主流程）")
    void recordUsageSwallowsException() {
        Mockito.doThrow(new RuntimeException("redis down"))
                .when(hashOps).increment(anyString(), anyString(), Mockito.anyLong());
        assertDoesNotThrow(() -> service.recordUsage(1L, new TokenBudgetService.Usage(10, 5)));
    }

    @Test
    @DisplayName("Usage.fromChat：null 用量与 null 计数都归零容错")
    void fromChatToleratesNulls() {
        assertEquals(0, TokenBudgetService.Usage.fromChat(null).total());
        TokenBudgetService.Usage u = TokenBudgetService.Usage.fromChat(new TokenUsage(null, null));
        assertEquals(0, u.inputTokens());
        assertEquals(0, u.outputTokens());
        TokenBudgetService.Usage real = TokenBudgetService.Usage.fromChat(new TokenUsage(80, 20));
        assertEquals(100, real.total());
    }

    @Test
    @DisplayName("userId 为空：不拦截也不记账")
    void nullUserSkipped() {
        assertFalse(service.exceeded(null));
        service.recordUsage(null, new TokenBudgetService.Usage(10, 5));
        verify(hashOps, never()).increment(anyString(), anyString(), Mockito.anyLong());
    }
}
