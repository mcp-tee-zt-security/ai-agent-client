package com.jupark.ai_agent_client.exception;

public class AIServiceUnavailableException extends RuntimeException {
    public AIServiceUnavailableException(String message) {
        super(message);
    }

    public AIServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
