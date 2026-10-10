package com.jupark.ai_agent_client;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.ollama.base-url=http://localhost:11434",
    "spring.ai.ollama.chat.options.model=qwen3:8b",
    "spring.ai.mcp.client.streamable-http.connections.order-server.url=http://localhost:8080"
})
class AiAgentServiceIntegrationTest {

    @Autowired
    private AiAgentService aiAgentService;

    @Test
    void contextLoads() {
        assertNotNull(aiAgentService);
    }

    // Note: This test requires Ollama and MCP Server to be running
    // Uncomment to run integration tests
    /*
    @Test
    void ask_WhenServicesAvailable_ReturnsAIResponse() {
        AIResponse response = aiAgentService.ask("What is 2 + 2?");
        
        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertNotNull(response.getTimestamp());
        assertNotNull(response.getModel());
        assertTrue(response.getProcessingTimeMs() >= 0);
    }
    */
}
