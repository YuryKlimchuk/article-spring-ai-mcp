package com.hydroyura.mcp.server.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ProductTools {

    @McpTool(description = "Получить список карт и заявок пользователя")
    public List<Map<String, Object>> getUserCards(
        @McpToolParam(description = "ID пользователя") String userId
    ) {
        // SELECT * FROM mcp_api.v_card_orders WHERE user_id = ?
        return List.of(Map.of("application_id", "app_9912", "step", "4/5"));
    }

    @McpTool(description = "Получить шаги обработки заявки на карту")
    public List<Map<String, Object>> getApplicationSteps(
        @McpToolParam(description = "ID заявки") String applicationId
    ) {
        // SELECT * FROM mcp_api.v_application_steps WHERE application_id = ?
        return List.of(Map.of("step", "COMPLIANCE_CHECK", "status", "FAILED"));
    }

    @McpTool(description = "Получить последние ошибки по пользователю из логов")
    public List<Map<String, Object>> getRecentAppErrors(
        @McpToolParam(description = "ID пользователя") String userId,
        @McpToolParam(description = "Окно в минутах") Integer minutes
    ) {
        // POST /_search по userId и сервису
        return List.of(Map.of("error", "BackOfficeServiceClient timeout", "count", 3));
    }
}
