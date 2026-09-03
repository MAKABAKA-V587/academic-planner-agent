package com.studentagent.studentagent;

import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.service.MemoryExtractService;
import com.studentagent.studentagent.service.ProfileService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆补偿任务（compensateNullVectorMemories）单元测试：
 * Chroma 写入失败后，vector_id 为空的记忆由定时任务补写——
 * 覆盖空表跳过、无时间记录跳过、正常回填（含 metadata 构造）、嵌入限流与通用异常的兜底。
 * 纯 Mockito 单元测试，不加载 Spring 上下文。
 */
class MemoryCompensationTest {

    private MemoryRecordMapper mapper;
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingModel embeddingModel;
    private MemoryExtractService service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(MemoryRecordMapper.class);
        embeddingStore = Mockito.mock(EmbeddingStore.class);
        embeddingModel = Mockito.mock(EmbeddingModel.class);
        service = new MemoryExtractService(
                Mockito.mock(ChatModel.class),
                Mockito.mock(StringRedisTemplate.class),
                embeddingStore,
                embeddingModel,
                mapper,
                Mockito.mock(MessageMapper.class),
                Mockito.mock(ProfileService.class));
    }

    private static Embedding embedding() {
        return new Embedding(new float[]{0.1f, 0.2f, 0.3f});
    }

    private static MemoryRecord pending(long id, String text, LocalDateTime createTime) {
        MemoryRecord r = new MemoryRecord();
        r.setRecordId(id);
        r.setUserId(1L);
        r.setMemoryText(text);
        r.setCreateTime(createTime);
        return r;
    }

    @Test
    @DisplayName("无待补记忆：直接返回，不触碰嵌入模型与向量库")
    void emptyPendingsSkipEverything() {
        when(mapper.findByNullVectorId(50)).thenReturn(List.of());

        service.compensateNullVectorMemories();

        verifyNoInteractions(embeddingModel, embeddingStore);
        verify(mapper, never()).updateVectorId(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    @DisplayName("全部记录无 create_time：无法构造 metadata，全部跳过待人工处理")
    void nullCreateTimeRecordsAllSkipped() {
        when(mapper.findByNullVectorId(50)).thenReturn(List.of(
                pending(1L, "薄弱科目-数学-计算薄弱", null),
                pending(2L, "学习目标-考研-目标985", null)));

        service.compensateNullVectorMemories();

        verifyNoInteractions(embeddingModel, embeddingStore);
        verify(mapper, never()).updateVectorId(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    @DisplayName("正常补偿：无时间记录被跳过，有效记录补写向量并回填 vector_id")
    @SuppressWarnings("unchecked")
    void compensationWritesVectorsAndBackfillsIds() {
        when(mapper.findByNullVectorId(50)).thenReturn(List.of(
                pending(1L, "薄弱科目-数学-计算薄弱", LocalDateTime.of(2026, 1, 15, 10, 0)),
                pending(2L, "学习目标-考研-目标985", null),
                pending(3L, "知识掌握-英语-阅读提升", LocalDateTime.of(2026, 1, 16, 9, 30))));
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(embedding(), embedding())));

        service.compensateNullVectorMemories();

        ArgumentCaptor<List<TextSegment>> segCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), anyList(), segCaptor.capture());
        List<TextSegment> segments = segCaptor.getValue();
        assertEquals(2, segments.size(), "只有 create_time 非空的记录参与补写");

        // metadata 必须带 userId/type/createTime，召回端依赖其做时间标注
        Object userId = segments.get(0).metadata().toMap().get("userId");
        Object type = segments.get(0).metadata().toMap().get("type");
        Object createTime = segments.get(0).metadata().toMap().get("createTime");
        assertEquals("1", String.valueOf(userId));
        assertEquals("extracted", String.valueOf(type));
        assertTrue(String.valueOf(createTime).length() >= 13, "createTime 应为毫秒时间戳");

        // 每条有效记录回填一次 vector_id（docId 由任务生成）
        verify(mapper, times(2)).updateVectorId(Mockito.anyLong(), Mockito.anyString());
        verify(mapper).updateVectorId(eq(1L), Mockito.anyString());
        verify(mapper).updateVectorId(eq(3L), Mockito.anyString());
        verify(mapper, never()).updateVectorId(eq(2L), anyString());
    }

    @Test
    @DisplayName("嵌入 API 限流（429）：静默放弃本轮，不抛异常，下轮重试")
    void rateLimitErrorSwallowedForNextRound() {
        when(mapper.findByNullVectorId(50)).thenReturn(List.of(
                pending(1L, "薄弱科目-数学-计算薄弱", LocalDateTime.of(2026, 1, 15, 10, 0))));
        when(embeddingModel.embedAll(anyList()))
                .thenThrow(new RuntimeException("429 Too Many Requests / rate limit exceeded"));

        service.compensateNullVectorMemories();

        verify(embeddingStore, never()).addAll(anyList(), anyList(), anyList());
        verify(mapper, never()).updateVectorId(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    @DisplayName("嵌入 API 通用异常：静默放弃本轮，不抛异常影响调度线程")
    void genericErrorSwallowedForNextRound() {
        when(mapper.findByNullVectorId(anyInt())).thenReturn(List.of(
                pending(1L, "薄弱科目-数学-计算薄弱", LocalDateTime.of(2026, 1, 15, 10, 0))));
        when(embeddingModel.embedAll(anyList()))
                .thenThrow(new RuntimeException("Connection reset"));

        service.compensateNullVectorMemories();

        verify(embeddingStore, never()).addAll(anyList(), anyList(), anyList());
        verify(mapper, never()).updateVectorId(Mockito.anyLong(), Mockito.anyString());
    }
}
