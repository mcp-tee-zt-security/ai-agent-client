package com.jupark.ai_agent_client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIResponse {
    private String answer;
    private LocalDateTime timestamp;
    private String model;
    private long processingTimeMs;
}
