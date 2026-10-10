package com.jupark.ai_agent_client.zt;

import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded JSON transport to ZT only. No redirect, fallback endpoint or application retry. */
@Component
public class ZtGatewayClient {
    public static class Failure extends RuntimeException {
        private final String status;
        Failure(String status, String message) { super(message); this.status = status; }
        public String status() { return status; }
    }
    private final ZtGatewayProperties config;
    private final HttpClient http;
    private final ObjectMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ZtGatewayClient(ZtGatewayProperties config) {
        this.config = config;
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds())).build();
    }
    public JsonNode parseArguments(String value) {
        try {
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 60000) throw new IllegalArgumentException();
            JsonNode result = json.readTree(value);
            if (result == null || !result.isObject()) throw new IllegalArgumentException();
            canonical(result, 0); return result;
        } catch (RuntimeException ex) { throw new Failure("NOT_EXECUTED", "Tool arguments must be a bounded, valid JSON object"); }
    }
    public String operationKey(String name, JsonNode arguments) {
        try {
            String value = name + ":" + canonical(arguments, 0);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private String canonical(JsonNode node, int depth) {
        if (depth > 16) throw new Failure("NOT_EXECUTED", "Tool argument nesting exceeds the client limit");
        if (node.isObject()) {
            List<String> keys = new ArrayList<>(node.propertyNames()); Collections.sort(keys);
            List<String> values = new ArrayList<>();
            for (String key : keys) values.add(json.writeValueAsString(key) + ":" + canonical(node.get(key), depth + 1));
            return "{" + String.join(",", values) + "}";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode value : node) values.add(canonical(value, depth + 1));
            return "[" + String.join(",", values) + "]";
        }
        if (node.isNumber()) {
            var value = node.decimalValue();
            if (value.precision() > 1024 || Math.abs((long) value.scale()) > 1024) throw new Failure("NOT_EXECUTED", "Unsupported numeric argument");
            return value.stripTrailingZeros().toPlainString();
        }
        return node.toString();
    }
    public JsonNode tools() {
        verifyIdentity();
        JsonNode tools = exchange("GET", "/v1/mcp/tools", null, false);
        if (!tools.isArray() || tools.size() > 500) throw new Failure("NOT_EXECUTED", "Invalid ZT tool catalog");
        Set<String> names = new HashSet<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (!name.matches("[A-Za-z0-9_.:-]{1,128}") || !names.add(name) || !tool.path("inputSchema").isObject()) {
                throw new Failure("NOT_EXECUTED", "Invalid or duplicate ZT tool definition");
            }
        }
        return tools;
    }
    private void verifyIdentity() {
        JsonNode context = exchange("GET", "/v1/mcp/management/context", null, false);
        if (!("client:" + config.getClientId()).equals(context.path("subject").asText())) {
            throw new Failure("NOT_EXECUTED", "ZT credential must authenticate the configured service client; shared administrator keys are not accepted");
        }
    }
    public ToolExecution call(String name, JsonNode arguments, String rpcId) {
        var body = json.createObjectNode(); body.put("jsonrpc", "2.0"); body.put("id", rpcId); body.put("method", "tools/call");
        var params = body.putObject("params"); params.put("name", name); params.set("arguments", arguments);
        try {
            JsonNode envelope = exchange("POST", "/v1/mcp/json-rpc", body, true);
            if (!"2.0".equals(envelope.path("jsonrpc").asText()) || !rpcId.equals(envelope.path("id").asText())
                    || envelope.has("result") == envelope.has("error")) {
                throw new Failure("UNKNOWN", "Invalid ZT RPC response; inspect gateway history before retrying");
            }
            if (envelope.has("error")) {
                int code = envelope.path("error").path("code").asInt();
                String state = Set.of(-32600, -32601, -32602, -32001).contains(code) ? "NOT_EXECUTED" : "UNKNOWN";
                throw new Failure(state, "ZT rejected or failed the RPC request (code " + code + "); inspect gateway history");
            }
            return outcome(name, rpcId, envelope.get("result"));
        } catch (Failure ex) { return failed(name, rpcId, ex); }
        catch (RuntimeException ex) { return failed(name, rpcId, new Failure("UNKNOWN", "Unexpected ZT response; inspect gateway history before retrying")); }
    }
    public JsonNode inspect(UUID callId) {
        verifyIdentity(); return exchange("GET", "/v1/mcp/calls/" + callId, null, false);
    }
    public ToolExecution resume(UUID callId) {
        try {
            verifyIdentity();
            ToolExecution value = outcome("saved-call", null, exchange("POST", "/v1/mcp/calls/" + callId + "/resume", json.createObjectNode(), true));
            if (!callId.toString().equals(value.callId())) throw new Failure("UNKNOWN", "ZT resume returned a different call ID");
            return value;
        } catch (Failure ex) {
            return new ToolExecution("saved-call", ex.status(), null, callId.toString(), null, null, ex.getMessage(), null);
        }
        catch (RuntimeException ex) {
            return new ToolExecution("saved-call", "UNKNOWN", null, callId.toString(), null, null, "Unexpected ZT resume response; inspect saved call", null);
        }
    }
    private ToolExecution failed(String name, String rpcId, Failure ex) {
        return new ToolExecution(name, ex.status(), null, null, null, rpcId, ex.getMessage(), null);
    }
    private ToolExecution outcome(String name, String rpcId, JsonNode result) {
        if (result == null || !result.isObject()) throw new Failure("UNKNOWN", "ZT returned no structured tool result");
        JsonNode security = result.path("_meta").path("zt.security");
        String state = security.path("status").asText("");
        String decision = security.path("decision").asText("");
        if (!Set.of("SUCCEEDED", "TOOL_ERROR", "DENIED", "PENDING_APPROVAL", "NOT_EXECUTED", "UNKNOWN", "REPLAY_BLOCKED").contains(state)
                || !Set.of("ALLOW", "DENY", "STEP_UP").contains(decision)) {
            throw new Failure("UNKNOWN", "Unknown ZT execution state; no automatic retry");
        }
        String callId = optionalId(security.get("callId"));
        String approvalId = optionalId(security.get("approvalId"));
        if (callId == null || "PENDING_APPROVAL".equals(state) && approvalId == null
                || "SUCCEEDED".equals(state) && (!"ALLOW".equals(decision) || result.path("isError").asBoolean())) {
            throw new Failure("UNKNOWN", "Inconsistent ZT result metadata");
        }
        return new ToolExecution(name, state, decision, callId, approvalId, rpcId,
            security.path("reason").asText(security.path("errorCode").asText("")), result);
    }
    private String optionalId(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try { return UUID.fromString(value.asText()).toString(); }
        catch (IllegalArgumentException ex) { throw new Failure("UNKNOWN", "Invalid ZT call or approval ID"); }
    }
    private JsonNode exchange(String method, String path, JsonNode body, boolean write) {
        if (config.getApiKey().isBlank()) throw new Failure("NOT_EXECUTED", "Set ZT_ORDER_AGENT_SECRET to the registered service-client secret");
        URI endpoint = URI.create(config.getBaseUrl().replaceAll("/+$", "") + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
            .header("Accept", "application/json").header("Content-Type", "application/json")
            .header("X-Client-Id", config.getClientId()).header("X-API-Key", config.getApiKey()).header("X-Tenant-Id", config.getTenantId());
        if (config.getWorkspaceId() != null && !config.getWorkspaceId().isBlank()) builder.header("X-Workspace-Id", config.getWorkspaceId());
        byte[] bytes = body == null ? new byte[0] : json.writeValueAsBytes(body);
        if (bytes.length > 65536) throw new Failure("NOT_EXECUTED", "ZT request exceeds the gateway input limit");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes));
        BoundedBody receiver = new BoundedBody(config.getMaxResponseBytes());
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(builder.build(), info -> receiver);
        String uncertain = write ? "UNKNOWN" : "NOT_EXECUTED";
        try {
            HttpResponse<byte[]> response = future.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                boolean rejectedBeforeExecution = Set.of(400, 401, 403, 404, 405, 413, 415, 422).contains(response.statusCode());
                throw new Failure(rejectedBeforeExecution ? "NOT_EXECUTED" : uncertain, "ZT gateway returned HTTP " + response.statusCode());
            }
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if (!"application/json".equalsIgnoreCase(type)) throw new Failure(uncertain, "ZT returned a non-JSON response");
            JsonNode value = json.readTree(response.body());
            if (value == null) throw new Failure(uncertain, "ZT returned an empty response");
            return value;
        } catch (Failure ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new Failure(uncertain, "ZT request interrupted; no automatic retry"); }
        catch (Exception ex) { throw new Failure(uncertain, "ZT transport or response failed; no automatic retry"); }
        finally { if (!future.isDone()) { receiver.cancel(); future.cancel(true); } }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) data.size() + buffer.remaining() > limit) {
                    cancel(); body.completeExceptionally(new IllegalStateException("ZT response exceeds limit")); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); data.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(data.toByteArray()); }
        void cancel() { if (subscription != null) subscription.cancel(); }
    }
}
