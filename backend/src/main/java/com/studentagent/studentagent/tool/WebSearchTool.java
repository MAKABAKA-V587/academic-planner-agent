package com.studentagent.studentagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.mapper.MessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具 —— 通过 Tavily Search API 搜索互联网获取实时信息，
 * 当本地知识库无法回答时，AI 可调用此工具获取最新资料。
 */
@Slf4j
@Component
public class WebSearchTool {

    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${tavily.api-key:}")
    private String apiKey;

    public WebSearchTool(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Tool(description = "搜索互联网获取最新的学习资料、技术文章、考试信息等。当本地知识库无法回答用户关于具体知识点、最新资讯、考试政策、题目解析等问题时使用")
    public String webSearch(
            @ToolParam(description = "搜索关键词或问题，如 '2025考研政治大纲变化' 'Java HashMap底层实现原理' '蓝桥杯真题解析' 等") String query,
            ToolContext toolContext) {

        log.info("[工具调用] webSearch: query={}", query);

        // 联网开关从 ToolContext 读取（流式端点工具在订阅线程执行，ThreadLocal 开关会丢失）
        if (!ToolContextHolder.webSearchEnabled(toolContext)) {
            return "联网搜索功能已关闭。如需开启，请点击输入框旁的联网开关。";
        }

        saveToolMessage("tool_call", "正在搜索: " + query, toolContext);

        if (apiKey == null || apiKey.isBlank()) {
            String msg = "联网搜索未配置 Tavily API Key，请先申请免费 Key 并配置到 application.yml 的 tavily.api-key";
            saveToolMessage("tool_result", msg, toolContext);
            return msg;
        }

        try {
            String result = doSearch(query);
            saveToolMessage("tool_result", result, toolContext);
            return result;
        } catch (Exception e) {
            log.error("Tavily 搜索失败: {}", e.getMessage());
            String fallback = "联网搜索暂时不可用（" + e.getMessage() + "），建议稍后重试或换个关键词。";
            saveToolMessage("tool_result", fallback, toolContext);
            return fallback;
        }
    }

    private String doSearch(String query) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("search_depth", "basic");
        body.put("max_results", 5);

        String jsonBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return "搜索失败，API 返回状态码: " + response.statusCode() + "，请稍后重试。";
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode results = root.get("results");

        if (results == null || results.isEmpty()) {
            return "未搜索到与「" + query + "」相关的结果，建议换更具体的关键词重试。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!--search_start-->\n");
        sb.append("🔍 **联网搜索**: ").append(query).append("\n\n");

        int count = 0;
        for (JsonNode item : results) {
            count++;
            String title = item.has("title") ? item.get("title").asText() : "无标题";
            String url = item.has("url") ? item.get("url").asText() : "";
            String content = item.has("content") ? item.get("content").asText() : "";

            // 截断过长内容
            if (content.length() > 400) {
                content = content.substring(0, 400) + "...";
            }

            sb.append(count).append(". **").append(title).append("**\n");
            sb.append("   ").append(content).append("\n");
            if (!url.isEmpty()) {
                sb.append("   来源: ").append(url).append("\n");
            }
            sb.append("\n");
        }

        sb.append("<!--search_end-->");
        return sb.toString();
    }

    private void saveToolMessage(String role, String content, ToolContext toolContext) {
        try {
            Long sessionId = ToolContextHolder.sessionId(toolContext);
            if (sessionId == null) return;

            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent(content);
            msg.setCreateTime(LocalDateTime.now());
            messageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("保存 WebSearch 工具消息失败: {}", e.getMessage());
        }
    }
}
