# ZT-governed AI Agent Client

Ollama selects tools; this application sends every business tool call through ZT.

```text
User /ai :9999 ---- Ollama / Qwen3 :11434
        |
        v
ZT Gateway :8080 (authentication, policy, risk and approval)
        |
        v
Registered order MCP server :9998 ---- Order Redis
```

The direct MCP connection to port 9998 is removed. There is no direct order-server fallback. Spring AI 2.0 ChatModel proposes tool calls and the application controls their execution. Credentials and endpoint configuration are never passed to the model.

The API binds to 127.0.0.1:9999 for local development. Add inbound authentication and per-user ownership controls before exposing it. ZT identifies the configured service client, not individual users of this local API.

## Register the service client

Create once using the ZT administrator credential:

```powershell
$headers = @{
    'X-API-Key' = 'dev-master-key'
    'X-Tenant-Id' = '11111111-1111-1111-1111-111111111111'
    'X-Workspace-Id' = '88888888-8888-8888-8888-888888888801'
}
$registration = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8080/v1/clients' -Headers $headers `
    -ContentType 'application/json' -Body '{
        "clientId":"order-ai-client",
        "name":"Order AI Client",
        "workspaceId":"88888888-8888-8888-8888-888888888801",
        "scopes":[]
    }'
$env:ZT_ORDER_AGENT_SECRET = $registration.secret
```

The secret is returned only once. Store it securely. Do not recreate an existing client to obtain its secret. The agent verifies that ZT authenticates it as client:order-ai-client and refuses shared administrator credentials.

For IDE launches, set ZT_ORDER_AGENT_SECRET in the Run Configuration. PowerShell environment changes do not configure an already running IDE process.

## Configuration

```properties
server.port=9999
server.address=127.0.0.1
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.options.model=qwen3:8b
zt.gateway.base-url=${ZT_GATEWAY_URL:http://localhost:8080}
zt.gateway.client-id=${ZT_ORDER_AGENT_CLIENT_ID:order-ai-client}
zt.gateway.api-key=${ZT_ORDER_AGENT_SECRET:}
zt.gateway.tenant-id=${ZT_TENANT_ID:11111111-1111-1111-1111-111111111111}
zt.gateway.workspace-id=${ZT_WORKSPACE_ID:88888888-8888-8888-8888-888888888801}
```

Use Java 17, Ollama, the ZT gateway and test order data. Defaults assume the agent runs on the host. Remote HTTP requires explicit zt.gateway.allow-http; otherwise use HTTPS. Requests use bounded response sizes and a total transport timeout, without redirects or application retries.

Missing credentials fail before execution; no administrator-key fallback exists. Startup does not register clients, seed orders or execute tools.

## Dashboard setup

Register the orders upstream at http://host.docker.internal:9998/mcp when ZT runs in Docker. Explicitly allow local development HTTP and load the tool definitions.

Register getOrders, getOrderStatus and cancelOrder as needed. Include client:order-ai-client in every required tool binding. Retain api-key on a separate line for administrator dashboard tests if desired. Binding changes invalidate pending approvals.

Activate a read policy:

```text
policy "allow_order_reads" {
  effect allow
  principal.type == "AI_AGENT"
  action == "mcp.tool.call"
  resource.type == "mcp_tool"
  condition {
    context.mcp.tool == "getOrders"
    or context.mcp.tool == "getOrderStatus"
  }
}
```

For DENY testing, activate cancellation denial. For approval testing, allow cancellation through policy and enable Require independent approval, or use STEP_UP policy. Risk and behavior checks remain authoritative.

If cancellation permits only TEST-ZT-001, use an isolated dataset. Listing unrelated pending orders may make the model request another order ID, correctly rejected by the schema.

## Run and call

```powershell
.\mvnw.cmd spring-boot:run
```

The existing endpoint remains:

```text
http://localhost:9999/ai?question=Show%20me%20all%20pending%20orders%20and%20cancel%20them.
```

Or send a POST:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:9999/ai' `
    -ContentType 'application/json' `
    -Body '{"question":"Show me all pending orders and cancel them."}'
```

Read the catalog at GET /ai/tools. Each new question starts a new workflow. Do not resubmit a question to resume pending or uncertain execution.

## Responses

Existing answer/model/timestamp/processing-time fields remain. Added fields are executionStatus, callId, approvalId and toolExecutions. Each tool record includes gateway state, RPC ID and filtered result.

| State | Meaning |
| --- | --- |
| NO_TOOL_EXECUTION | Model returned without a recorded tool call |
| COMPLETED | Model returned after successful gateway results; inspect their business outcomes |
| DENIED | ZT refused a tool; remaining tools stopped |
| PENDING_APPROVAL | Saved request awaits approval; no later tools run |
| UNKNOWN | Execution may have occurred; no automatic retry |
| NOT_EXECUTED | Rejected input or pre-execution failure; workflow stopped |
| TOOL_ERROR | Upstream reported a tool error; workflow stopped |
| REPLAY_BLOCKED | ZT did not dispatch a repeated operation |
| MODEL_ERROR_AFTER_TOOLS | Model failed after tools; review recorded results |
| LIMIT_REACHED | Planning limit reached; earlier results remain visible |

Tools run sequentially and stop immediately on non-success. Earlier operations are not rolled back. Identical names and canonical arguments execute once per question and reuse the earlier result. Cached reads may describe an earlier state. This is not persistent deduplication between separate questions or upstream exactly-once execution.

SUCCEEDED describes gateway receipt/recording. ORDER_CANNOT_BE_CANCELLED is not successful cancellation. Natural-language answers are not authoritative execution evidence.

## Approve and resume

Save the returned callId and approvalId. Use an independent approver in ZT MCP approvals, then resume through the original agent:

```powershell
Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:9999/ai/calls/<callId>/resume'
```

Inspect at GET /ai/calls/<callId>. Resume forwards only the saved ID, accepts no replacement arguments or approval ID, and does not rerun Ollama or continue the whole task. ZT rechecks ownership, binding, expiry, approval and current policy. Resuming the first pending cancellation in a multi-order question does not automatically process other orders.

If an uncertain response lacks a call ID, retain rpcId and reconcile gateway history/upstream state before another operation.

## Evidence and limits

ZT Call history should identify client:order-ai-client. Prove DENY using its gateway record and zero increase in the upstream cancelOrder counter. Read tools may execute before cancellation is denied.

This change does not add an upstream counter or prevent a separate application from accessing the order server directly. Network and upstream credentials must enforce that boundary.

Builds, tests, model requests and live business execution were not run for this implementation.

API references: [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html), [ToolCallback](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/tool/ToolCallback.html).
