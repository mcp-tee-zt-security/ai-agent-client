package com.jupark.ai_agent_client.zt;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Request-local, serialized gate. No later tool runs after a stop state. */
public final class ZtToolSession {
    private final ZtGatewayClient gateway;
    private final int limit;
    private final String requestId = UUID.randomUUID().toString();
    private final Map<String, ToolExecution> executions = new LinkedHashMap<>();
    private ToolExecution stopped;
    private int invocations;

    public ZtToolSession(ZtGatewayClient gateway, int limit) { this.gateway = gateway; this.limit = limit; }
    public ToolCallback[] callbacks(JsonNode tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (JsonNode tool : tools) {
            String description = tool.path("description").asText("");
            if (description.isBlank()) description = "ZT-governed tool " + tool.path("name").asText();
            ToolDefinition definition = ToolDefinition.builder().name(tool.path("name").asText())
                .description(description)
                .inputSchema(tool.get("inputSchema").toString()).build();
            callbacks.add(new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return definition; }
                @Override public String call(String input) { return execute(definition, input); }
            });
        }
        return callbacks.toArray(ToolCallback[]::new);
    }
    private synchronized String execute(ToolDefinition definition, String input) {
        if (stopped != null) throw blocked(definition);
        String name = definition.name();
        try {
            if (++invocations > limit) throw new ZtGatewayClient.Failure("NOT_EXECUTED", "Agent tool-call limit reached; workflow stopped");
            JsonNode arguments = gateway.parseArguments(input);
            String key = gateway.operationKey(name, arguments);
            ToolExecution execution = executions.get(key);
            if (execution == null) {
                execution = gateway.call(name, arguments, requestId + ":" + key);
                executions.put(key, execution);
            }
            if (!execution.succeeded()) { stopped = execution; throw blocked(definition); }
            return execution.result().toString();
        } catch (ZtGatewayClient.Failure ex) {
            stopped = new ToolExecution(name, ex.status(), null, null, null, null, ex.getMessage(), null);
            executions.put("stopped:" + invocations, stopped); throw blocked(definition);
        }
    }
    private ToolExecutionException blocked(ToolDefinition definition) {
        return new ToolExecutionException(definition, new IllegalStateException("ZT workflow stopped: " + stopped.status()));
    }
    public synchronized ToolExecution stopped() { return stopped; }
    public synchronized List<ToolExecution> executions() { return List.copyOf(executions.values()); }
}
