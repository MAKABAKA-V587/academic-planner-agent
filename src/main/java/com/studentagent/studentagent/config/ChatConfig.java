package com.studentagent.studentagent.config;

import com.studentagent.studentagent.tool.CalendarTool;
import com.studentagent.studentagent.tool.KnowledgeRetrievalTool;
import com.studentagent.studentagent.tool.LearningPlanTool;
import com.studentagent.studentagent.tool.ReviewPlanTool;
import com.studentagent.studentagent.tool.WebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

    /** 主聊天 Client（DeepSeek-V3 + 工具） */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  LearningPlanTool planTool,
                                  KnowledgeRetrievalTool knowledgeTool,
                                  CalendarTool calendarTool,
                                  WebSearchTool webSearchTool,
                                  ReviewPlanTool reviewPlanTool) {
        return builder
                .defaultTools(planTool, knowledgeTool, calendarTool, webSearchTool, reviewPlanTool)
                .build();
    }

    /** 报告/标签生成 Client（DeepSeek-V3，质量高） */
    @Bean
    public ChatClient reportChatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-ai/DeepSeek-V3")
                        .maxTokens(8192)
                        .build())
                .build();
    }

    /** 流式聊天 Client（无工具，纯文本流） */
    @Bean
    public ChatClient streamChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
