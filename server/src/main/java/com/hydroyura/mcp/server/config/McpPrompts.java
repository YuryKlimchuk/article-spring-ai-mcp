package com.hydroyura.mcp.server.config;

import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpPrompts {

    @McpPrompt(name = "investigate-card-opening",
               description = "Investigate card opening issue for a customer")
    public GetPromptResult investigateCardOpening(GetPromptRequest request) {
        String userId = request.arguments().getOrDefault("userId", "unknown").toString();
        String backOfficeCardId = request.arguments().getOrDefault("backOfficeCardId", "unknown").toString();

        var message = PromptMessage.builder(Role.USER,
            new TextContent(String.format("""
                Customer %s cannot open a card. Back-office: %s.
                Use the card-opening-flow resource for context.
                1. getUserCards → 2. getApplicationSteps
                → 3. getRecentAppErrors → 4. getPendingCompliance
                """, userId, backOfficeCardId))).build();

        return GetPromptResult.builder(List.of(message)).build();
    }

    @McpPrompt(name = "auto-investigate-payment",
               description = "Automated investigation of high-latency alert")
    public GetPromptResult autoInvestigatePayment(GetPromptRequest request) {
        String service = request.arguments().getOrDefault("service", "unknown").toString();
        String metric = request.arguments().getOrDefault("metric", "unknown").toString();
        String threshold = request.arguments().getOrDefault("threshold", "unknown").toString();
        String currentValue = request.arguments().getOrDefault("currentValue", "unknown").toString();

        var message = PromptMessage.builder(Role.USER,
            new TextContent(String.format("""
                🚨 ALERT: %s %s = %s (threshold: %s)
                Investigate following the runbook.
                🔒 ROOT CAUSE: ...
                📊 EVIDENCE: ...
                ✅ ACTION PLAN: ...
                🎫 Jira ticket: ...
                """, service, metric, currentValue, threshold))).build();

        return GetPromptResult.builder(List.of(message)).build();
    }
}
