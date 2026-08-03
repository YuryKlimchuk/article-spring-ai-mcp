# Реализация на Spring Boot

> **Код на GitHub:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server), [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client). Код ниже — скелет с заглушками, полная реализация в репозитории.

Теперь код. Все tools, resources и промпты из сценариев.

## Зависимости

Всё, что нужно для MCP — две зависимости:

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
        type: SYNC   # SYNC — синхронный режим; альтернатива: ASYNC для reactive
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



## Tools: @McpTool и @McpToolParam

Все 11 инструментов — по одному шаблону. Два примера (полный код — в репозитории):

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

Остальные 9 методов — тот же шаблон, различаются только источником данных:

Методы возвращают моки (полный код с `JdbcTemplate`, `RestClient` и клиентами внешних API — в репозитории). Остальные 9 методов — тот же шаблон: Prometheus-метрики, OpenSearch-ошибки, Jenkins-деплои, Jira-тикеты, запросы к `product_db` и `backoffice_db`.

Ключевой момент: `description` в аннотации читает LLM при выборе инструмента. Не жалейте деталей.

## Resources и Prompts: аннотации @McpResource и @McpPrompt

Ресурсы и промпты объявляются декларативно — аннотациями `@McpResource` и `@McpPrompt` на методах компонента. Spring AI регистрирует их при старте:

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

    // Другие ресурсы: payment-db-schema (DDL), alert-runbook-payment (порядок действий)
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

Второй промпт `auto-investigate-payment` — аналогично: принимает `service`, `metric`, `threshold`, `currentValue` и возвращает структурированный шаблон расследования.

Промпт задаёт сценарий, ресурсы дают контекст. LLM сама сопоставляет шаги с инструментами.

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

Контроллер саппорта — обычный Spring MVC, ничего специфичного для MCP:

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

Ресурсы и промпты подгружаются автоматически. `ChatClient` регистрирует tools как tool callbacks — LLM сама решает, что вызывать.
