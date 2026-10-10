package com.jupark.ai_agent_client.exception;

public class MCPServerException extends RuntimeException {
    public MCPServerException(String message) {
        super(message);
    }

    public MCPServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
