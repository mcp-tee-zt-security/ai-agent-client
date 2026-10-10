package com.jupark.ai_agent_client.zt;

import tools.jackson.databind.JsonNode;

/** Gateway state and filtered result, never a claim that business side effects were verified. */
public record ToolExecution(String toolName, String status, String decision, String callId,
        String approvalId, String rpcId, String reason, JsonNode result) {
    public boolean succeeded() { return "SUCCEEDED".equals(status); }
}
