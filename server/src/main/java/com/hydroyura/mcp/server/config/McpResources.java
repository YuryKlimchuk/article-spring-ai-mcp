package com.hydroyura.mcp.server.config;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public class McpResources {

    @McpResource(uri = "card-opening-flow", name = "card-opening-flow",
                 description = "Card application step flow diagram", mimeType = "text/plain")
    public String cardOpeningFlow() {
        return """
            1. APPLICATION_CREATED → 2. KYC_CHECK → 3. SCORING
            → 4. COMPLIANCE_CHECK (back-office) → 5. ACTIVATION
            If stuck at step 4: use getApplicationSteps + getPendingCompliance
            """;
    }

    @McpResource(uri = "payment-db-schema", name = "payment-db-schema",
                 description = "DDL for transactions, settlements tables and indexes", mimeType = "text/plain")
    public String paymentDbSchema() {
        return """
            CREATE TABLE transactions (id, status, batch_id, ...);
            CREATE INDEX idx_tx_batch_id ON transactions(batch_id);
            -- IMPORTANT: no index on (batch_id, status)
            """;
    }

    @McpResource(uri = "alert-runbook-payment", name = "alert-runbook-payment",
                 description = "Runbook for payment-high-latency alert", mimeType = "text/plain")
    public String alertRunbook() {
        return """
            1. getServiceMetrics → 2. getPodRestarts → 3. getBlockingSessions
            → 4. getSlowQueries → 5. getRecentDeploys → 6. getServiceErrors
            → 7. createJiraTicket
            """;
    }
}
