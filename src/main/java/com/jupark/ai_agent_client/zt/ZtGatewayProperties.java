package com.jupark.ai_agent_client.zt;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "zt.gateway")
public class ZtGatewayProperties {
    private String baseUrl = "http://localhost:8080";
    private String clientId = "order-ai-client";
    private String apiKey = "";
    private String tenantId = "11111111-1111-1111-1111-111111111111";
    private String workspaceId = "88888888-8888-8888-8888-888888888801";
    private int timeoutSeconds = 30;
    private int maxResponseBytes = 2097152;
    private int maxToolCalls = 20;
    private boolean allowHttp = false;

    @PostConstruct
    public void validate() {
        URI uri = URI.create(baseUrl);
        boolean local = uri.getHost() != null && Set.of("localhost", "127.0.0.1", "host.docker.internal").contains(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && (local || allowHttp))) {
            throw new IllegalArgumentException("ZT gateway requires a fixed HTTP(S) URL without embedded credentials");
        }
        UUID.fromString(tenantId);
        if (workspaceId != null && !workspaceId.isBlank()) UUID.fromString(workspaceId);
        if (clientId == null || !clientId.matches("[A-Za-z0-9_.:-]{1,128}") || timeoutSeconds < 1 || timeoutSeconds > 120
                || maxResponseBytes < 1024 || maxResponseBytes > 16777216 || maxToolCalls < 1 || maxToolCalls > 100
                || apiKey == null || apiKey.length() > 4096 || apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid ZT gateway credentials or limits");
        }
    }
}
