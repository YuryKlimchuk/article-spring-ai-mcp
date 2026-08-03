package com.hydroyura.mcp.server.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PaymentTools {

    @McpTool(description = "Get list of blocking sessions in the payments database")
    public List<Map<String, Object>> getBlockingSessions() {
        // SELECT * FROM mcp_api.v_blocking_sessions
        return List.of(Map.of(
            "blocking_pid", 8732,
            "blocking_query_short", "UPDATE transactions SET status='SETTLED'...",
            "blocked_count", 55
        ));
    }

    @McpTool(description = "Get top slow SQL queries from pg_stat_statements")
    public List<Map<String, Object>> getSlowQueries(
        @McpToolParam(description = "Database name") String database,
        @McpToolParam(description = "Time window in minutes") Integer minutes,
        @McpToolParam(description = "Max results") Integer limit
    ) {
        // SELECT * FROM mcp_api.v_slow_queries LIMIT ?
        return List.of(Map.of(
            "query_sample", "UPDATE transactions...",
            "mean_time_ms", 4800,
            "calls", 156
        ));
    }

    @McpTool(description = "Get service metrics from Prometheus: p99_latency, db_connections_active, http_errors")
    public Map<String, Object> getServiceMetrics(
        @McpToolParam(description = "Service name") String service,
        @McpToolParam(description = "Metric: p99_latency, db_connections_active, ...") String metric,
        @McpToolParam(description = "Time window in minutes") Integer minutes
    ) {
        // GET /api/v1/query?query={metric}{service}[{minutes}m]
        return Map.of("metric", metric, "value", 5200);
    }

    @McpTool(description = "Get recent error logs from OpenSearch for a service")
    public List<Map<String, Object>> getServiceErrors(
        @McpToolParam(description = "Service name") String service,
        @McpToolParam(description = "Time window in minutes") Integer minutes
    ) {
        // POST /_search with query by service and timestamp
        return List.of(Map.of("error", "could not obtain lock", "count", 450));
    }

    @McpTool(description = "Get recent deployments from Jenkins")
    public List<Map<String, Object>> getRecentDeploys(
        @McpToolParam(description = "Service name") String service
    ) {
        // GET /job/{service}/api/json
        return List.of(Map.of("version", "v2.7.0", "status", "SUCCESS"));
    }

    @McpTool(description = "Create a Jira ticket with investigation results")
    public Map<String, Object> createJiraTicket(
        @McpToolParam(description = "Project key") String project,
        @McpToolParam(description = "Ticket summary") String summary,
        @McpToolParam(description = "Full investigation report") String description
    ) {
        // POST /rest/api/2/issue
        return Map.of("ticketId", project + "-8872", "status", "created");
    }

    @McpTool(description = "Get pod restarts from Kubernetes API")
    public List<Map<String, Object>> getPodRestarts(
        @McpToolParam(description = "Service name") String service,
        @McpToolParam(description = "Time window in minutes") Integer minutes
    ) {
        // GET /api/v1/pods?labelSelector=app={service}
        return List.of(Map.of("reason", "OOMKilled", "count", 3, "time", "00:13"));
    }
}
