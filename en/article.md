# Spring AI + MCP: Giving LLMs Safe Access to Your Systems

> **TL;DR**
>
> - **Problem:** LLMs are isolated from your systems — they can't check database slowdowns, call an API, or read logs.
> - **Solution:** The Model Context Protocol (MCP) gives LLMs controlled access to tools, data, and workflows. One server, any client.
> - **Implementation:** Spring AI 2.0 boils it down to three annotations: `@McpTool`, `@McpResource`, `@McpPrompt`. Fifteen lines of code per tool.
> - **Result:** Support investigates incidents in a minute instead of an hour. Night-time alerts are triaged automatically. Zero code duplication across clients.

> 📦 **Source code:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server) · [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client)

## Introduction

You ask an LLM about the latest Spring release — it tells you that version doesn't exist. You ask it to check why your database is slow — it can't. The reason is simple: an LLM is isolated from the outside world. It needs a controlled way to call functions and fetch up-to-date data.

Previously, this was solved with function calling — a mechanism where function descriptions are embedded directly into the LLM request. The problem? Every provider (OpenAI, Anthropic, Google) implemented it differently, and tools were tightly coupled to a specific application. Reusing them in another client without rewriting was impossible.

MCP solves this at the protocol level: tools live on a server, not tied to a host or provider. One server — any MCP-compatible client. That's why the industry is moving toward MCP: tools become a resource, not a feature of a particular provider.

**Model Context Protocol (MCP)** was created by Anthropic in late 2024. Today the protocol is supported by Zed IDE, Sourcegraph, and a growing open-source community. It's a client-server protocol built on JSON-RPC: the server provides tools, resources, and prompts; the client is any MCP-compatible application.

**Spring AI 2.0** makes integration straightforward: annotations on methods, auto-configuration for transport, `ChatClient` on the client side. We'll walk through the MCP architecture and its three key concepts — Tool, Resource, and Prompt. Then we'll build a working Spring Boot prototype and cover PII protection using a database VIEW.

---

# How MCP Works

## Architecture: Host, Client, Server

MCP is built on a three-component **host-client-server** architecture:

```
┌─────────────────────────────────────────────────────┐
│ Host (Claude Desktop, IDE, your application)        │
│  ┌───────────────┐                                  │
│  │  MCP Client   │── JSON-RPC ──┐                   │
│  └───────────────┘              │                   │
└─────────────────────────────────┼───────────────────┘
                                  │
                  ┌───────────────▼───────────────────┐
                  │  MCP Server (Spring Boot)         │
                  │  ┌──────┐ ┌──────────┐ ┌───────┐  │
                  │  │Tools │ │Resources │ │Prompts│  │
                  │  └──────┘ └──────────┘ └───────┘  │
                  └───────────────────────────────────┘
```

- **Host** — the application with an LLM (Claude Desktop, IDE, your chat). Contains an MCP client.
- **MCP Client** — connects to the server, sends JSON-RPC, returns results to the host.
- **MCP Server** — a Spring Boot service with tools, resources, and prompts. Doesn't know who connected.

## Three Primitives and Who Controls Them

The key difference between Tool, Resource, and Prompt is **who** decides to use them:

- **Tool** — an action (SQL, API, write). Invoked by the **LLM**: the model receives a list of tools and decides when to call which one.
- **Resource** — data (DB schema, docs). Loaded by the **host**: the server declares resources, the client decides when to pull them into context.
- **Prompt** — a message template with parameters. Chosen by the **user** via `/` or a menu.

> Resources and Prompts are optional MCP capabilities. In practice, most servers get by with Tools alone.

## Transport: stdio and Streamable HTTP

MCP runs on top of JSON-RPC, with messages physically transmitted in one of two ways:

**stdio** — local: the host launches a jar as a subprocess, communication over stdin/stdout. No authentication needed.

**Streamable HTTP** (replaced SSE in 2025) — remote: `POST /mcp` over HTTP, authentication via Origin validation or OAuth 2.1.

**⚠️ Important for stdio**: the server writes only JSON-RPC to stdout. `System.out.println`, the Spring Boot banner, any other output breaks the protocol. The fix:

```yaml
spring.main.banner-mode: off
logging.file.name: /dev/stderr   # Linux. Windows: logging.file.name: NUL or a file path
```

## Lifecycle

1. **Initialize** — client and server exchange capabilities: "I have tools, resources, prompts."
2. **tools/list** — the client requests the tool list, Spring AI registers them with the `ChatClient`.
3. **tools/call** — the LLM selected a tool → the client executes the call on the server → the result is returned to the LLM.
4. **Response** — the LLM processes the result and generates the final answer.

Now let's move on to practice: two end-to-end scenarios from retail banking.

> **Docs:** [MCP specification](https://spec.modelcontextprotocol.io) — full protocol description; [Spring AI MCP reference](https://docs.spring.io/spring-ai/reference/2.0/api/mcp/mcp-annotations-server.html) — how to wire the server into Spring Boot.

---

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

---

# Spring Boot Implementation

> **Code on GitHub:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server), [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client). The code below is a skeleton with stubs; the full implementation is omitted for brevity.

Now the code. All the tools, resources, and prompts from the scenarios.

## Dependencies

Everything you need for MCP — just two dependencies:

```xml
<!-- Server: MCP auto-configuration -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- Client: server connection + tool registration in ChatClient -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
    <version>2.0.0</version>
</dependency>
```

## Configuration

The server needs the bare minimum — a name, protocol, and port:

```yaml
server:
  port: 8081

spring:
  ai:
    mcp:
      server:
        name: Bank MCP Server
        protocol: STREAMABLE
        type: SYNC   # SYNC — synchronous mode; alternative: ASYNC for reactive
```

The client needs the server address and a model. Transport is specified via a config section (`streamable-http`, `sse`, or `stdio`), not a `type` field:

```yaml
spring:
  ai:
    mcp:
      client:
        type: SYNC
        streamable-http:
          connections:
            bank-server:
              url: http://localhost:8081
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5:1.5b
```



## Tools: @McpTool and @McpToolParam

All 11 tools follow the same pattern. Two examples (full code is in the repository):

```java
@Component
public class PaymentTools {

    @McpTool(description = "Get list of blocking sessions in the payments database")
    public List<Map<String, Object>> getBlockingSessions() {
        return jdbcTemplate.queryForList(
            "SELECT blocked_pid, blocking_pid, blocking_duration " +
            "FROM mcp_api.v_blocking_sessions");
    }

    @McpTool(description = "Get top slow SQL queries from pg_stat_statements")
    public List<Map<String, Object>> getSlowQueries(
        @McpToolParam(description = "Database name") String database,
        @McpToolParam(description = "Time window in minutes") Integer minutes,
        @McpToolParam(description = "Max results") Integer limit
    ) {
        return jdbcTemplate.queryForList(
            "SELECT query_short, calls, mean_time_ms " +
            "FROM mcp_api.v_slow_queries LIMIT ?", limit);
    }
}
```

The methods return mocks (full code with `JdbcTemplate`, `RestClient`, and external API clients is in the repository). The remaining 9 methods follow the same pattern: Prometheus metrics, OpenSearch errors, Jenkins deployments, Jira tickets, and queries to `product_db` and `backoffice_db`.

The key takeaway: the `description` in the annotation is what the LLM reads when choosing a tool. Be generous with detail.

## Resources and Prompts: @McpResource and @McpPrompt

Resources and prompts are declared declaratively — via `@McpResource` and `@McpPrompt` annotations on component methods. Spring AI registers them at startup:

```java
@Component
public class McpResources {

    @McpResource(uri = "card-opening-flow", name = "card-opening-flow",
                 description = "Card application step flow diagram", mimeType = "text/plain")
    public String cardOpeningFlow() {
        return """
            1. APPLICATION_CREATED → ... → 5. ACTIVATION
            If stuck at step 4: use getApplicationSteps + getPendingCompliance
            """;
    }

    // Other resources: payment-db-schema (DDL), alert-runbook-payment (action plan)
}
```

```java
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
}
```

The second prompt, `auto-investigate-payment`, works the same way: it takes `service`, `metric`, `threshold`, and `currentValue` and returns a structured investigation template.

Prompts define the scenario, resources provide context. The LLM matches steps to tools on its own.

## Client: ChatClient + Auto-Connected Tools

`SyncMcpToolCallbackProvider` automatically collects all tool callbacks from MCP servers. One line — and they're registered with `ChatClient`:

```java
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  SyncMcpToolCallbackProvider toolCallbackProvider) {
        return builder
            .defaultTools(toolCallbackProvider.getToolCallbacks())
            .build();
    }
}
```

The support controller is a plain Spring MVC controller — nothing MCP-specific:

```java
@RestController
public class SupportController {

    private final ChatClient chatClient;

    public SupportController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping("/support/ask")
    public Map<String, Object> ask(
        @RequestParam String userId,
        @RequestParam String backOfficeCardId
    ) {
        String response = chatClient.prompt()
            .user(String.format("""
                Customer %s cannot open a card. Back-office: %s.
                Use the card-opening-flow resource for context.
                1. getUserCards → 2. getApplicationSteps
                → 3. getRecentAppErrors → 4. getPendingCompliance
                """, userId, backOfficeCardId))
            .call()
            .content();

        return Map.of("userId", userId, "response", response);
    }
}
```

Resources and prompts are loaded automatically. `ChatClient` registers tools as tool callbacks — the LLM decides on its own what to invoke.

---

# Conclusion

We started with an LLM that didn't know about the new Spring release. We ended with an architecture where the LLM investigates alerts on its own and produces an action plan. An MCP server built on Spring Boot serves both support engineers and automation.

## When MCP Is Not Needed

MCP doesn't replace REST APIs, gRPC, or Kafka — it solves a different problem: giving LLMs standardized access to tools, resources, and prompts. Three cases where MCP only adds complexity:

- **The service already has a well-designed REST contract.** If the clients are other microservices or frontends rather than LLMs, an MCP layer is redundant.
- **High-frequency calls.** Every tool call adds 50–200 ms of network overhead. For real-time streams, use a direct API instead of a chain of tool calls.
- **Critical mutations without confirmation.** An LLM shouldn't unilaterally decide to delete a record or change a limit. Tools with side effects require human approval — MCP is for diagnostics, not `DELETE FROM users`.

## Key Takeaways

**Spring AI 2.0 is a mature tool for production integrations.** Annotate a method with `@McpTool` — it's immediately available to the LLM. The client connects with a single line: `.defaultTools(toolCallbackProvider.getToolCallbacks())`. STDIO and Streamable HTTP work out of the box.

**Database-level security is not optional — it's architecture.** The `mcp_readonly` role has SELECT only on views in the `mcp_api` schema. The LLM physically cannot read PII or execute DML.

**Tool, Resource, Prompt — three entry points.** The LLM initiates a Tool call, the host loads a Resource, and the user selects a Prompt. A clean separation of responsibilities.

---

The main takeaway: MCP transforms an LLM from an isolated advisor into an agent that works with live data from your systems. Not "I assume," but "I checked — here are the facts and a plan of action." Spring AI 2.0 makes this accessible to any Java developer: three annotations and auto-configuration — everything you need for production.

## References

- [MCP Specification](https://spec.modelcontextprotocol.io)
- [Spring AI MCP Reference](https://docs.spring.io/spring-ai/reference/2.0/api/mcp/mcp-annotations-server.html)
- **Source code:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server), [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client)
- [MCP Hub](https://github.com/modelcontextprotocol/servers) — catalog of ready-made servers
