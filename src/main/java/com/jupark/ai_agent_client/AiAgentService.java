package com.jupark.ai_agent_client;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

@Service
public class AiAgentService {

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpTools;

    public AiAgentService(
            ChatClient.Builder chatClientBuilder,
            ToolCallbackProvider mcpTools) {

        this.chatClient = chatClientBuilder.build();
        this.mcpTools = mcpTools;
    }

    public String ask(String question) {

        return chatClient.prompt()
                .user(question)
                .tools(mcpTools)
                .call()
                .content();
    }
}