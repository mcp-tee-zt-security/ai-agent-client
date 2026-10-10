package com.jupark.ai_agent_client;

import com.jupark.ai_agent_client.dto.AIResponse;
import com.jupark.ai_agent_client.exception.AIServiceUnavailableException;
import com.jupark.ai_agent_client.exception.MCPServerException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class AiAgentService {

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpTools;

    @Value("${spring.ai.ollama.chat.options.model}")
    private String model;

    public AiAgentService(ChatClient.Builder chatClientBuilder, ToolCallbackProvider mcpTools) {
        this.chatClient = chatClientBuilder.build();
        this.mcpTools = mcpTools;
    }

    public AIResponse ask(String question) {
        log.info("Processing AI request with question: {}", question);
        long startTime = System.currentTimeMillis();

        try {
            String answer = chatClient.prompt()
                    .user(question)
                    .tools(mcpTools)
                    .call()
                    .content();

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("AI response generated successfully in {} ms", processingTime);

            return AIResponse.builder()
                    .answer(answer)
                    .timestamp(LocalDateTime.now())
                    .model(model)
                    .processingTimeMs(processingTime)
                    .build();

        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            log.error("Failed to generate AI response after {} ms", processingTime, e);
            
            if (e.getMessage() != null && e.getMessage().contains("Ollama")) {
                throw new AIServiceUnavailableException("AI service (Ollama) is unavailable: " + e.getMessage(), e);
            } else if (e.getMessage() != null && e.getMessage().contains("MCP")) {
                throw new MCPServerException("MCP Server error: " + e.getMessage(), e);
            }
            throw new AIServiceUnavailableException("Failed to process AI request: " + e.getMessage(), e);
        }
    }
}