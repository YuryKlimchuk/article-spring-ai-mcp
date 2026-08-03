package com.hydroyura.mcp.server.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProductTools {

    @McpTool(description = "Get user cards and card applications")
    public List<Map<String, Object>> getUserCards(
        @McpToolParam(description = "User ID") String userId
    ) {
        // SELECT * FROM mcp_api.v_card_orders WHERE user_id = ?
        return List.of(Map.of("application_id", "app_9912", "step", "4/5"));
    }

    @McpTool(description = "Get application processing steps for a card application")
    public List<Map<String, Object>> getApplicationSteps(
        @McpToolParam(description = "Application ID") String applicationId
    ) {
        // SELECT * FROM mcp_api.v_application_steps WHERE application_id = ?
        return List.of(Map.of("step", "COMPLIANCE_CHECK", "status", "FAILED"));
    }

    @McpTool(description = "Get recent application errors from logs for a user")
    public List<Map<String, Object>> getRecentAppErrors(
        @McpToolParam(description = "User ID") String userId,
        @McpToolParam(description = "Time window in minutes") Integer minutes
    ) {
        // POST /_search by userId and service
        return List.of(Map.of("error", "BackOfficeServiceClient timeout", "count", 3));
    }
}
