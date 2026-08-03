package com.hydroyura.mcp.server.config;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public class McpResources {

    @McpResource(uri = "card-opening-flow", name = "card-opening-flow",
                 description = "Схема шагов оформления карты", mimeType = "text/plain")
    public String cardOpeningFlow() {
        return """
            1. APPLICATION_CREATED → 2. KYC_CHECK → 3. SCORING
            → 4. COMPLIANCE_CHECK (back-office) → 5. ACTIVATION
            Если зависла на шаге 4: getApplicationSteps + getPendingCompliance
            """;
    }

    @McpResource(uri = "payment-db-schema", name = "payment-db-schema",
                 description = "DDL таблиц transactions, settlements и индексы", mimeType = "text/plain")
    public String paymentDbSchema() {
        return """
            CREATE TABLE transactions (id, status, batch_id, ...);
            CREATE INDEX idx_tx_batch_id ON transactions(batch_id);
            -- ВАЖНО: нет индекса на (batch_id, status)
            """;
    }

    @McpResource(uri = "alert-runbook-payment", name = "alert-runbook-payment",
                 description = "Runbook для алёрта payment-high-latency", mimeType = "text/plain")
    public String alertRunbook() {
        return """
            1. getServiceMetrics → 2. getPodRestarts → 3. getBlockingSessions
            → 4. getSlowQueries → 5. getRecentDeploys → 6. getServiceErrors
            → 7. createJiraTicket
            """;
    }
}
