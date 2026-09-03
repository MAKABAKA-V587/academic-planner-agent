package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.tool.KnowledgeRetrievalTool;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索调试接口 —— 供离线评测脚本（backend/eval/rag_eval.py）调用。
 * 只读检索，不写任何数据；topk/threshold 可覆盖，用于参数对比实验。
 */
@RestController
@RequestMapping("/api/debug/rag")
@RequiredArgsConstructor
public class RagDebugController {

    private final KnowledgeRetrievalTool knowledgeRetrievalTool;

    @GetMapping("/search")
    public Result<Map<String, Object>> search(
            @RequestParam String subject,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "5") int topk,
            @RequestParam(defaultValue = "0.5") double threshold) {

        long start = System.currentTimeMillis();
        KnowledgeRetrievalTool.RetrievalResult r =
                knowledgeRetrievalTool.retrieve(subject, keyword, topk, threshold);
        long costMs = System.currentTimeMillis() - start;

        List<Map<String, Object>> candidates = r.candidates().stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", c.source());
                    m.put("score", c.score());
                    m.put("text", c.text());
                    return m;
                })
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retriever", r.retriever());
        data.put("candidates", candidates);
        data.put("costMs", costMs);
        return Result.ok(data);
    }
}
