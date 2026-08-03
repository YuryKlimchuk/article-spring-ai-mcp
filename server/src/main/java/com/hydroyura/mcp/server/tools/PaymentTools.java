package com.hydroyura.mcp.server.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PaymentTools {

    @McpTool(description = "Получить список блокирующих сессий в БД платежей")
    public List<Map<String, Object>> getBlockingSessions() {
        // SELECT * FROM mcp_api.v_blocking_sessions
        return List.of(Map.of(
            "blocking_pid", 8732,
            "blocking_query_short", "UPDATE transactions SET status='SETTLED'...",
            "blocked_count", 55
        ));
    }

    @McpTool(description = "Получить топ самых медленных SQL-запросов")
    public List<Map<String, Object>> getSlowQueries(
        @McpToolParam(description = "Название БД") String database,
        @McpToolParam(description = "Окно в минутах") Integer minutes,
        @McpToolParam(description = "Лимит результатов") Integer limit
    ) {
        // SELECT * FROM mcp_api.v_slow_queries LIMIT ?
        return List.of(Map.of(
            "query_sample", "UPDATE transactions...",
            "mean_time_ms", 4800,
            "calls", 156
        ));
    }

    @McpTool(description = "Получить метрики сервиса из Prometheus: p99_latency, db_connections_active, http_errors")
    public Map<String, Object> getServiceMetrics(
        @McpToolParam(description = "Сервис") String service,
        @McpToolParam(description = "Метрика: p99_latency, db_connections_active, ...") String metric,
        @McpToolParam(description = "Окно в минутах") Integer minutes
    ) {
        // GET /api/v1/query?query={metric}{service}[{minutes}m]
        return Map.of("metric", metric, "value", 5200);
    }

    @McpTool(description = "Получить последние ошибки сервиса из логов OpenSearch")
    public List<Map<String, Object>> getServiceErrors(
        @McpToolParam(description = "Сервис") String service,
        @McpToolParam(description = "Окно в минутах") Integer minutes
    ) {
        // POST /_search с query по service и timestamp
        return List.of(Map.of("error", "could not obtain lock", "count", 450));
    }

    @McpTool(description = "Получить список последних деплоев из Jenkins")
    public List<Map<String, Object>> getRecentDeploys(
        @McpToolParam(description = "Сервис") String service
    ) {
        // GET /job/{service}/api/json
        return List.of(Map.of("version", "v2.7.0", "status", "SUCCESS"));
    }

    @McpTool(description = "Создать тикет в Jira по результатам расследования")
    public Map<String, Object> createJiraTicket(
        @McpToolParam(description = "Ключ проекта") String project,
        @McpToolParam(description = "Краткое описание") String summary,
        @McpToolParam(description = "Полный отчёт") String description
    ) {
        // POST /rest/api/2/issue
        return Map.of("ticketId", project + "-8872", "status", "created");
    }

    @McpTool(description = "Получить рестарты подов из Kubernetes API")
    public List<Map<String, Object>> getPodRestarts(
        @McpToolParam(description = "Сервис") String service,
        @McpToolParam(description = "Окно в минутах") Integer minutes
    ) {
        // GET /api/v1/pods?labelSelector=app={service}
        return List.of(Map.of("reason", "OOMKilled", "count", 3, "time", "00:13"));
    }
}
