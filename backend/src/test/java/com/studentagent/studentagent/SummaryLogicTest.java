package com.studentagent.studentagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.SessionMapper;
import com.studentagent.studentagent.service.chat.ChatHistoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 方案A（旧轮次滚动摘要压缩）单元测试：
 * 滚动摘要成功推进水位线、LLM 返回为空不推进、去抖阈值不触发。
 * 目标类为拆分后的 ChatHistoryStore（摘要逻辑自 ChatService 迁入）。
 * 纯 Mockito 单元测试，不加载 Spring 上下文。
 */
class SummaryLogicTest {

    private ChatModel chatModel;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ChatHistoryStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModel = Mockito.mock(ChatModel.class);
        messageMapper = Mockito.mock(MessageMapper.class);
        sessionMapper = Mockito.mock(SessionMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);
        Mockito.when(sessionMapper.findById(Mockito.anyLong())).thenReturn(null); // 无持久化摘要

        store = new ChatHistoryStore(
                redisTemplate,
                new ObjectMapper(),
                messageMapper,
                sessionMapper,
                chatModel);
    }

    private void invokeDoSummarize(long fromId, long toId) throws Exception {
        Method m = ChatHistoryStore.class.getDeclaredMethod("doSummarize", Long.class, long.class, long.class);
        m.setAccessible(true);
        m.invoke(store, 1L, fromId, toId);
    }

    private void invokeSummarizeIfNeeded() throws Exception {
        Method m = ChatHistoryStore.class.getDeclaredMethod("summarizeIfNeeded", Long.class);
        m.setAccessible(true);
        m.invoke(store, 1L);
    }

    private ChatMessage msg(String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("滚动摘要：滑出段原文+LLM输出 → 双写状态并推进水位线")
    void doSummarizeAdvancesWatermark() throws Exception {
        Mockito.when(messageMapper.findSummarizableBetween(1L, 0L, 50L, 60))
                .thenReturn(List.of(msg("user", "我9月要考研"), msg("assistant", "已生成复习计划")));
        Mockito.when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("用户9月考研，已生成复习计划")).build());

        invokeDoSummarize(0L, 50L);

        Mockito.verify(sessionMapper).updateSummary(1L, "用户9月考研，已生成复习计划", 50L);
        Mockito.verify(valueOps).set(eq("chat:summary:1"), anyString(), eq(7 * 24 * 3600L), eq(TimeUnit.SECONDS));
        Mockito.verify(redisTemplate).delete("chat:summary:lock:1"); // finally 释放锁
    }

    @Test
    @DisplayName("LLM返回为空 → 水位线不推进，等待下轮重试")
    void doSummarizeBlankKeepsWatermark() throws Exception {
        Mockito.when(messageMapper.findSummarizableBetween(1L, 0L, 50L, 60))
                .thenReturn(List.of(msg("user", "我9月要考研")));
        Mockito.when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("   ")).build());

        invokeDoSummarize(0L, 50L);

        Mockito.verify(sessionMapper, Mockito.never()).updateSummary(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("去抖：滑出不足3轮 → 不触发摘要任务")
    void summarizeIfNeededDebounce() throws Exception {
        Mockito.when(messageMapper.getWindowStartMessageId(1L, 8)).thenReturn(100L);
        Mockito.when(messageMapper.countUserMessagesBetween(1L, 0L, 100L)).thenReturn(2);

        invokeSummarizeIfNeeded();

        Mockito.verify(valueOps, Mockito.never())
                .setIfAbsent(anyString(), anyString(), anyLong(), Mockito.any(TimeUnit.class));
    }

    @Test
    @DisplayName("滑出达到3轮 → 加锁并提交摘要任务")
    void summarizeIfNeededTriggers() throws Exception {
        Mockito.when(messageMapper.getWindowStartMessageId(1L, 8)).thenReturn(100L);
        Mockito.when(messageMapper.countUserMessagesBetween(1L, 0L, 100L)).thenReturn(3);
        Mockito.when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), Mockito.any(TimeUnit.class)))
                .thenReturn(true);
        Mockito.when(messageMapper.findSummarizableBetween(eq(1L), eq(0L), eq(100L), Mockito.anyInt()))
                .thenReturn(List.of());
        Mockito.when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("空")).build());

        invokeSummarizeIfNeeded();

        Mockito.verify(valueOps).setIfAbsent(eq("chat:summary:lock:1"), anyString(), anyLong(),
                Mockito.any(TimeUnit.class));
        // 异步任务可能尚未执行完，用 timeout 验证锁最终被释放
        Mockito.verify(redisTemplate, Mockito.timeout(2000)).delete("chat:summary:lock:1");
    }

    @Test
    @DisplayName("总轮数未超窗口 → 直接返回，不查水位线之外内容")
    void summarizeIfNeededNoOverflow() throws Exception {
        Mockito.when(messageMapper.getWindowStartMessageId(1L, 8)).thenReturn(null);

        invokeSummarizeIfNeeded();

        Mockito.verify(messageMapper, Mockito.never())
                .countUserMessagesBetween(Mockito.anyLong(), anyLong(), anyLong());
        Mockito.verify(sessionMapper, Mockito.never()).updateSummary(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("锁被占用（另一实例在摘） → 不重复提交任务")
    void summarizeIfNeededLockHeld() throws Exception {
        Mockito.when(messageMapper.getWindowStartMessageId(1L, 8)).thenReturn(100L);
        Mockito.when(messageMapper.countUserMessagesBetween(1L, 0L, 100L)).thenReturn(6);
        Mockito.when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), Mockito.any(TimeUnit.class)))
                .thenReturn(false);

        invokeSummarizeIfNeeded();

        // 锁被占用：不应再消费待摘原文
        Mockito.verify(messageMapper, Mockito.never())
                .findSummarizableBetween(anyLong(), anyLong(), anyLong(), Mockito.anyInt());
    }
}
