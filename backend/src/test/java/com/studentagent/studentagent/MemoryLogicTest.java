package com.studentagent.studentagent;

import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.service.MemoryExtractService;
import com.studentagent.studentagent.service.ProfileService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * 记忆逻辑单元测试：极性判断、类别对立、以及「新声明覆盖旧记忆」的完整流程。
 * 纯 Mockito 单元测试，不加载 Spring 上下文。
 */
class MemoryLogicTest {

    private MemoryExtractService newMockService() {
        return Mockito.mock(MemoryExtractService.class, Mockito.CALLS_REAL_METHODS);
    }

    private boolean oppositePolarity(String a, String b) throws Exception {
        Method m = MemoryExtractService.class.getDeclaredMethod("oppositePolarity", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(newMockService(), a, b);
    }

    private boolean isOppositeCategory(String a, String b) throws Exception {
        Method m = MemoryExtractService.class.getDeclaredMethod("isOppositeCategory", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(newMockService(), a, b);
    }

    @Test
    @DisplayName("极性相反：薄弱 vs 没问题 → 冲突")
    void polarityNegativeVsPositive() throws Exception {
        assertTrue(oppositePolarity("数学比较薄弱", "数学现在没问题了"));
        assertTrue(oppositePolarity("已经掌握了链表", "链表还是很薄弱"));
        assertTrue(oppositePolarity("高数不会做", "高数现在会了"));
    }

    @Test
    @DisplayName("同向表述不构成冲突")
    void polaritySameDirection() throws Exception {
        assertFalse(oppositePolarity("数学比较薄弱", "英语也比较薄弱"));
        assertFalse(oppositePolarity("掌握了链表", "掌握了二叉树"));
    }

    @Test
    @DisplayName("类别对立：薄弱科目 ↔ 知识掌握")
    void categoryOpposition() throws Exception {
        assertTrue(isOppositeCategory("薄弱科目", "知识掌握"));
        assertTrue(isOppositeCategory("知识掌握", "薄弱科目"));
        assertFalse(isOppositeCategory("薄弱科目", "学习目标"));
        assertFalse(isOppositeCategory("学习习惯", "知识掌握"));
    }

    @Test
    @DisplayName("完整流程：新掌握声明覆盖旧薄弱记忆（删除 MySQL + Chroma）")
    void processAndStoreOverridesOldMemory() throws Exception {
        MemoryRecordMapper mapper = Mockito.mock(MemoryRecordMapper.class);
        EmbeddingStore<TextSegment> embeddingStore = Mockito.mock(EmbeddingStore.class);
        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embedAll(Mockito.anyList())).thenReturn(Response.from(List.of()));

        MemoryRecord old = new MemoryRecord();
        old.setRecordId(100L);
        old.setUserId(1L);
        old.setMemoryText("薄弱科目-数学-数学比较薄弱");
        old.setVectorId("vec-100");
        Mockito.when(mapper.findByUserId(1L)).thenReturn(List.of(old));

        MemoryExtractService svc = new MemoryExtractService(
                Mockito.mock(ChatModel.class),
                Mockito.mock(StringRedisTemplate.class),
                embeddingStore,
                embeddingModel,
                mapper,
                Mockito.mock(MessageMapper.class),
                Mockito.mock(ProfileService.class));

        String raw = "知识掌握-数学-数学现在没问题了";
        int count = svc.processAndStore(1L, raw);

        assertEquals(1, count, "新记忆应被写入");
        Mockito.verify(mapper).deleteByIds(anyList());
        Mockito.verify(embeddingStore).removeAll(List.of("vec-100"));
    }

    @Test
    @DisplayName("同向新记忆不会误删旧记忆")
    void processAndStoreKeepsSameDirection() throws Exception {
        MemoryRecordMapper mapper = Mockito.mock(MemoryRecordMapper.class);
        EmbeddingStore<TextSegment> embeddingStore = Mockito.mock(EmbeddingStore.class);
        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        Mockito.when(embeddingModel.embedAll(Mockito.anyList())).thenReturn(Response.from(List.of()));

        MemoryRecord old = new MemoryRecord();
        old.setRecordId(100L);
        old.setUserId(1L);
        old.setMemoryText("薄弱科目-数学-数学比较薄弱");
        old.setVectorId("vec-100");
        Mockito.when(mapper.findByUserId(1L)).thenReturn(List.of(old));

        MemoryExtractService svc = new MemoryExtractService(
                Mockito.mock(ChatModel.class),
                Mockito.mock(StringRedisTemplate.class),
                embeddingStore,
                embeddingModel,
                mapper,
                Mockito.mock(MessageMapper.class),
                Mockito.mock(ProfileService.class));

        // 同向（也是薄弱），仅去重阈值放行，不应触发覆盖删除
        String raw = "薄弱科目-数学-数学较薄弱需要继续加强";
        int count = svc.processAndStore(1L, raw);

        assertEquals(1, count);
        Mockito.verify(mapper, Mockito.never()).deleteByIds(anyList());
    }
}
