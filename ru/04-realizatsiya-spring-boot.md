# Реализация на Spring Boot

Теория и сценарии — это хорошо, но давайте посмотрим на код. В этой главе — черновик реализации: все tools, resources и промпты из сценариев, но только Spring AI-специфичные фрагменты. Код на английском — так `description` аннотаций читает LLM.

## Зависимости

Вся магия MCP — в двух dependency:

```xml
<!-- Сервер: автоконфигурация MCP -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    <version>2.0.0</version>
</dependency>

<!-- Клиент: подключение к серверу + регистрация tools в ChatClient -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
    <version>2.0.0</version>
</dependency>
```

## Конфигурация

Серверу нужен минимум — имя, протокол и порт:

```yaml
server:
  port: 8081

spring:
  ai:
    mcp:
      server:
        name: Bank MCP Server
        protocol: STREAMABLE
        type: SYNC
```

Клиенту — адрес сервера и модель. Транспорт задаётся разделом в конфигурации (`streamable-http`, `sse` или `stdio`), а не полем `type`:

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

Никакого ручного JSON-RPC. Транспорт — на автоконфигурации.

## Tools: @McpTool и @McpToolParam

Все семь инструментов из сценария 2 и четыре из сценария 1 — по одному шаблону: `@McpTool` на методе, `@McpToolParam` на параметрах. Ниже — полный набор с заглушками вместо реальных запросов к БД и API.

```java
// Scenario 2: alert investigation
@Component
public class PaymentTools {

    @McpTool(description = "Get list of blocking sessions in the payments database")
    public List<Map<String, Object>> getBlockingSessions() {
        // SELECT * FROM mcp_api.v_blocking_sessions
        return List.of(Map.of("blocking_pid", 8732, "blocked_count", 55));
    }

    @McpTool(description = "Get top slow SQL queries from pg_stat_statements")
    public List<Map<String, Object>> getSlowQueries(
        @McpToolParam(description = "Database name") String database,
        @McpToolParam(description = "Time window in minutes") Integer minutes,
        @McpToolParam(description = "Max results") Integer limit
    ) {
        // SELECT * FROM mcp_api.v_slow_queries LIMIT ?
        return List.of(Map.of("query_sample", "UPDATE transactions...", "mean_time_ms", 4800));
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

    @McpTool(description = "Get pod restarts from Kubernetes API")
    public List<Map<String, Object>> getPodRestarts(
        @McpToolParam(description = "Service name") String service,
        @McpToolParam(description = "Time window in minutes") Integer minutes
    ) {
        // GET /api/v1/pods?labelSelector=app={service}
        return List.of(Map.of("reason", "OOMKilled", "count", 3, "time", "00:13"));
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
}

// Scenario 1: card issue investigation
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

@Component
public class BackOfficeTools {

    @McpTool(description = "Get pending compliance checks for a user")
    public List<Map<String, Object>> getPendingCompliance(
        @McpToolParam(description = "User ID") String userId
    ) {
        // SELECT * FROM mcp_api.v_pending_compliance WHERE user_id = ?
        return List.of(Map.of("check_id", 887, "status", "PENDING"));
    }
}
```

Ключевой момент: `description` в аннотациях — это то, что читает LLM при выборе инструмента. Пишите на естественном языке, не жалейте деталей.

## Resources и Prompts: аннотации @McpResource и @McpPrompt

Ресурсы и промпты объявляются декларативно — аннотациями `@McpResource` и `@McpPrompt` на методах компонента. Spring AI сам регистрирует их при старте:

```java
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

    @McpPrompt(name = "auto-investigate-payment",
               description = "Automated investigation of high-latency alert")
    public GetPromptResult autoInvestigatePayment(GetPromptRequest request) {
        String service = request.arguments().getOrDefault("service", "unknown").toString();
        String metric = request.arguments().getOrDefault("metric", "unknown").toString();
        String threshold = request.arguments().getOrDefault("threshold", "unknown").toString();
        String currentValue = request.arguments().getOrDefault("currentValue", "unknown").toString();

        var message = PromptMessage.builder(Role.USER,
            new TextContent(String.format("""
                🚨 ALERT: %s %s = %s (threshold: %s)
                Investigate following the runbook.
                🔒 ROOT CAUSE: ...
                📊 EVIDENCE: ...
                ✅ ACTION PLAN: ...
                🎫 Jira ticket: ...
                """, service, metric, currentValue, threshold))).build();

        return GetPromptResult.builder(List.of(message)).build();
    }
}
```

Промпт не знает, какие tools доступны — он задаёт сценарий. LLM сама сопоставляет шаги с инструментами. Ресурсы дают ей контекст: схема БД помогает понять структуру таблиц, runbook — порядок действий, схема шагов заявки — на каком этапе какие tools нужны.

> **Альтернативный подход:** если нужна более тонкая настройка, ресурсы и промпты можно зарегистрировать программно через `@Bean` с `McpServerFeatures.SyncResourceSpecification` и `McpServerFeatures.SyncPromptSpecification`. Но для большинства случаев аннотаций достаточно.

## Клиент: ChatClient + автоподключение tools

`SyncMcpToolCallbackProvider` автоматически собирает все tool callbacks с MCP-серверов. Одна строка — и они зарегистрированы в `ChatClient`:

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

Клиентский контроллер саппорта — ничего специфичного для MCP, обычный Spring MVC:

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

Ресурсы и промпты с MCP-сервера подгружаются автоматически через протокол. `ChatClient` сам регистрирует доступные tools как tool callbacks и передаёт их LLM в каждом запросе. LLM сама решает, какие tools вызвать.

## До и после

Без MCP LLM гадает. С MCP — оперирует фактами:

```
Without MCP:
«I don't have access to your database or logs.
 Possible reasons: failure, verification, card limit.»

With MCP:
«Application app_9912 is stuck at COMPLIANCE_CHECK step.
 Three BackOfficeServiceClient timeouts. Check #887 — PENDING.
 On-call team already notified. Card will be issued after recovery.»
```
