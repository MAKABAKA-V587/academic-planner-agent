package com.studentagent.studentagent.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j 手动工具调用执行器。
 *
 * 从工具 Bean 反射生成 {@link ToolSpecification} 列表，并在工具循环中按名称执行对应方法。
 * 工具方法内的用户身份/会话/联网开关通过 {@link ToolContextHolder}（ThreadLocal）获取，
 * 手动循环与模型调用在同一线程内执行，ThreadLocal 不会丢失。
 */
public class ToolCallExecutor {

    private final List<ToolSpecification> specifications;
    private final Map<String, ToolExecutor> executors;

    public ToolCallExecutor(Object... tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, ToolExecutor> execs = new HashMap<>();
        for (Object tool : tools) {
            for (Method method : tool.getClass().getMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                    specs.add(spec);
                    execs.put(spec.name(), new DefaultToolExecutor(tool, method));
                }
            }
        }
        this.specifications = specs;
        this.executors = execs;
    }

    public List<ToolSpecification> specifications() {
        return specifications;
    }

    /** 执行一次工具调用，返回工具结果文本 */
    public String execute(ToolExecutionRequest request) {
        ToolExecutor executor = executors.get(request.name());
        if (executor == null) {
            return "错误：找不到工具 " + request.name();
        }
        return executor.execute(request, null);
    }
}
