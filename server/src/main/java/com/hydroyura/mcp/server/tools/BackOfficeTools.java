package com.hydroyura.mcp.server.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BackOfficeTools {

    @McpTool(description = "Получить зависшие compliance-проверки для пользователя")
    public List<Map<String, Object>> getPendingCompliance(
        @McpToolParam(description = "ID пользователя") String userId
    ) {
        // SELECT * FROM mcp_api.v_pending_compliance WHERE user_id = ?
        return List.of(Map.of("check_id", 887, "status", "PENDING"));
    }
}
