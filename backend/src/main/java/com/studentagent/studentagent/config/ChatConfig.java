package com.studentagent.studentagent.config;

import com.studentagent.studentagent.tool.CalendarTool;
import com.studentagent.studentagent.tool.KnowledgeRetrievalTool;
import com.studentagent.studentagent.tool.LearningPlanTool;
import com.studentagent.studentagent.tool.ReviewPlanTool;
import com.studentagent.studentagent.tool.ToolCallExecutor;
import com.studentagent.studentagent.tool.WebSearchTool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class ChatConfig {

    /** 主对话模型（DeepSeek-V3，LangChain4j starter 自动配置，这里建立别名便于注入） */
    @Bean
    @Primary
    public ChatModel chatModel(@Qualifier("openAiChatModel") ChatModel model) {
        return model;
    }

    /** 流式对话模型（DeepSeek-V3，真流式：token 边生成边推，首 token 毫秒级） */
    @Bean
    @Primary
    public StreamingChatModel streamingChatModel(@Qualifier("openAiStreamingChatModel") StreamingChatModel model) {
        return model;
    }

    /** 报告/标签生成模型（DeepSeek-V3，质量高，与主模型同配置） */
    @Bean
    public ChatModel reportChatModel(@Qualifier("openAiChatModel") ChatModel model) {
        return model;
    }

    /** Embedding 模型（Qwen3-Embedding-0.6B，starter 自动配置） */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel(@Qualifier("openAiEmbeddingModel") EmbeddingModel model) {
        return model;
    }

    /** Chroma 向量库（LangChain4j 无 chroma starter 自动配置，编程式创建） */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${langchain4j.chroma.base-url:http://localhost:8000}") String baseUrl,
            @Value("${langchain4j.chroma.collection-name:student_memory}") String collectionName) {
        return ChromaEmbeddingStore.builder()
                .baseUrl(baseUrl)
                .collectionName(collectionName)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    /** 工具执行器（LangChain4j 手动工具循环：反射生成规格 + 按名称执行） */
    @Bean
    public ToolCallExecutor toolCallExecutor(LearningPlanTool planTool,
                                             KnowledgeRetrievalTool knowledgeTool,
                                             CalendarTool calendarTool,
                                             WebSearchTool webSearchTool,
                                             ReviewPlanTool reviewPlanTool) {
        return new ToolCallExecutor(planTool, knowledgeTool, calendarTool, webSearchTool, reviewPlanTool);
    }
}
