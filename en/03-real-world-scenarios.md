# Real-World Scenarios

Let's take banking retail: three services (`payment-service`, `product-service`, `back-office-service`), each with its own database, communicating via API. Two scenarios on a single MCP server.

## Scenario 1: Support Investigates a Card Issue

A customer calls: "I'm trying to open a virtual card, clicked three times — error." Support opens a chat. Without MCP: DBeaver → copy-paste into chat → Grafana → screenshot → chat again. With MCP: a single question.

**Server composition:** `getUserCards`, `getApplicationSteps`, `getRecentAppErrors`, `getPendingCompliance` — tools; `card-opening-flow` — resource; `investigate-card-opening` — prompt.

**Investigation flow:**

1. Support selects the `investigate-card-opening` prompt with parameters `userId=user_45678`, `backOfficeCardId=bo_887`. The host loads the `card-opening-flow` resource.
2. The LLM calls `getUserCards` → no active cards in the database, application `app_9912` is at step 4/5.
3. The LLM calls `getApplicationSteps` → step `COMPLIANCE_CHECK` failed with a timeout.
4. The LLM calls `getRecentAppErrors` + `getPendingCompliance` → 3 timeouts from BackOfficeServiceClient, check #887 stuck at PENDING.
5. The LLM responds: "The application is stuck on compliance. The on-call team has already been paged. The customer doesn't need to do anything."

Support asked a question — the LLM walked the chain: cards → steps → errors → compliance.

## Scenario 2: Alert — the System Investigates Itself

Nighttime. Alertmanager: `payment-service p99_latency = 5.2s` (threshold 500ms). The host receives a webhook and kicks off an investigation.

**Server composition:** `getServiceMetrics` (Prometheus), `getPodRestarts` (K8s), `getBlockingSessions`, `getSlowQueries` (DB), `getServiceErrors` (OpenSearch), `getRecentDeploys` (Jenkins), `createJiraTicket` (Jira) — tools; `payment-db-schema`, `alert-runbook-payment` — resources; `auto-investigate-payment` — prompt.

**Investigation flow:**

1. Alertmanager sends a webhook. The host loads the `payment-db-schema` and `alert-runbook` resources, applies the `auto-investigate-payment` prompt.
2. The LLM calls `getServiceMetrics` → p99_latency = 5.2s (up from 200ms).
3. The LLM calls `getPodRestarts` → 3 restarts with OOMKilled at 00:13.
4. The LLM calls `getBlockingSessions` → pid 8732 holds a RowExclusiveLock, 55 sessions waiting.
5. The LLM calls `getSlowQueries` → `UPDATE transactions SET status='SETTLED'` — mean_time 4.8s.
6. The LLM calls `getServiceErrors` → 450× "could not obtain lock".
7. The LLM calls `createJiraTicket` → ticket PAY-8872 created.

The LLM produced a diagnosis: OOM → restarts → connection pool exhausted → blocking pid 8732. Action plan: `pg_terminate_backend(8732)`, memory limit 4Gi, index on `transactions(batch_id)`, connection pool 150.

## Security: Why the LLM Never Sees Raw Data

> Before we dive into implementation — an important architectural decision.

Names like `mcp_api.v_blocking_sessions` are not a typo — they're an architectural choice. The `mcp_api` schema with VIEWs, accessible under the `mcp_readonly` role with SELECT-only permissions:

```sql
-- Read-only role, public schema is locked down
CREATE ROLE mcp_readonly WITH LOGIN PASSWORD '***' CONNECTION LIMIT 5;
GRANT SELECT ON ALL TABLES IN SCHEMA mcp_api TO mcp_readonly;

CREATE VIEW mcp_api.v_blocking_sessions AS
SELECT blocked.pid, LEFT(blocked.query, 200) AS blocked_query_short,
       blocking.pid AS blocking_pid
FROM pg_stat_activity blocked
JOIN pg_stat_activity blocking
    ON pg_blocking_pids(blocked.pid) @> ARRAY[blocking.pid]
WHERE blocked.wait_event_type = 'Lock' AND blocked.datname = 'payments_db';
```

Similar VIEWs in `product_db` and `backoffice_db` — with PII masking and date restrictions.

**Why this matters specifically for MCP.** A regular REST API also masks PII. But MCP introduces a unique risk: the LLM itself decides which Tool to call and with what parameters. Protection at the VIEW level means: whatever the LLM comes up with — it physically cannot read `card_number` or delete a row. Not "we hope the LLM behaves," but "we designed it so the LLM has no choice."

---

Both scenarios run on the same server — only the initiator of the interaction changes. These are the very same Tools and Resources we're about to implement in code: each one is 10–15 lines on Spring Boot.
