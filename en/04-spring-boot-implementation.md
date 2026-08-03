# Spring Boot Implementation

**Code on GitHub:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server), [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client). The code below is a skeleton with stubs; the full implementation is omitted for brevity.

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
