package com.hydroyura.mcp.client.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AlertController {

    private final ChatClient chatClient;

    public AlertController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping("/alert/webhook")
    public Map<String, Object> webhook(@RequestBody Map<String, Object> alert) {
        String service = (String) alert.getOrDefault("service", "unknown");
        String metric = (String) alert.getOrDefault("metric", "p99_latency");
        String threshold = (String) alert.getOrDefault("threshold", "500ms");
        String currentValue = String.valueOf(alert.getOrDefault("value", "N/A"));

        String response = chatClient.prompt()
            .user(String.format("""
                🚨 ALERT: %s %s = %s (threshold: %s)
                Investigate following the runbook.
                🔒 ROOT CAUSE: ...
                📊 EVIDENCE: ...
                ✅ ACTION PLAN: ...
                🎫 Jira ticket: ...
                """, service, metric, currentValue, threshold))
            .call()
            .content();

        return Map.of("alert", alert, "diagnosis", response);
    }
}
