package com.jupark.ai_agent_client;

import com.jupark.ai_agent_client.dto.AIRequest;
import com.jupark.ai_agent_client.dto.AIResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiAgentController {

    private final AiAgentService aiAgentService;

    @GetMapping
    public ResponseEntity<AIResponse> ask(@RequestParam String question) {
        log.info("REST API: Received AI request: {}", question);
        AIResponse response = aiAgentService.ask(question);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<AIResponse> askWithBody(@Valid @RequestBody AIRequest request) {
        log.info("REST API: Received AI request via POST: {}", request.getQuestion());
        AIResponse response = aiAgentService.ask(request.getQuestion());
        return ResponseEntity.ok(response);
    }
}