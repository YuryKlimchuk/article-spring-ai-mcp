# Реализация на Spring Boot

Теория и сценарии — это хорошо, но давайте посмотрим на код. В этой главе — черновик реализации: все tools, resources и промпты из сценариев, но только Spring AI-специфичные фрагменты.

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
spring:
  ai:
    mcp:
      server:
        name: "Bank MCP Server"
        protocol: STREAMABLE
        streamable-http:
          port: 8081
```

Клиенту — адрес сервера и модель:

```yaml
spring:
  ai:
    mcp:
      client:
        connections:
          bank-server:
            type: STREAMABLE
            url: http://localhost:8081
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen3:14b
```

Никакого ручного JSON-RPC. Транспорт — на автоконфигурации.

## Tools: @McpTool и @McpToolParam

Все семь инструментов из сценария 2 и четыре из сценария 1 — по одному шаблону: `@McpTool` на методе, `@McpToolParam` на параметрах. Ниже — полный набор с заглушками вместо реальных запросов к БД и API.

```java
// Сценарий 2: расследование алёрта
@Component
public class PaymentTools {

    @McpTool(description = "Получить список блокирующих сессий в БД платежей.")
    public List<Map<String, Object>> getBlockingSessions() {
        // SELECT * FROM mcp_api.v_blocking_sessions
        return List.of(Map.of("blocking_pid", 8732, "blocked_count", 55));
    }

    @McpTool(description = "Получить топ самых медленных SQL-запросов.")
    public List<Map<String, Object>> getSlowQueries(
        @McpToolParam(description = "Название БД") String database,
        @McpToolParam(description = "Окно в минутах") Integer minutes,
        @McpToolParam(description = "Лимит результатов") Integer limit
    ) {
        // SELECT * FROM mcp_api.v_slow_queries LIMIT ?
        return List.of(Map.of("query_sample", "UPDATE transactions...", "mean_time_ms", 4800));
    }

    @McpTool(description = "Получить метрики сервиса из Prometheus.")
    public Map<String, Object> getServiceMetrics(
        @McpToolParam(description = "Сервис") String service,
        @McpToolParam(description = "Метрика: p99_latency, db_connections_active, ...") String metric,
        @McpToolParam(description = "Окно в минутах") Integer minutes
    ) {
        // GET /api/v1/query?query={metric}{service}[{minutes}m]
        return Map.of("metric", metric, "value", 5200);
    }

    @McpTool(description = "Получить рестарты подов из Kubernetes API.")
    public List<Map<String, Object>> getPodRestarts(
        @McpToolParam(description = "Сервис") String service,
        @McpToolParam(description = "Окно в минутах") Integer minutes
    ) {
        // GET /api/v1/pods?labelSelector=app={service}
        return List.of(Map.of("reason", "OOMKilled", "count", 3, "time", "00:13"));
    }

    @McpTool(description = "Получить последние ошибки сервиса из логов OpenSearch.")
    public List<Map<String, Object>> getServiceErrors(
        @McpToolParam(description = "Сервис") String service,
        @McpToolParam(description = "Окно в минутах") Integer minutes
    ) {
        // POST /_search с query по service и timestamp
        return List.of(Map.of("error", "could not obtain lock", "count", 450));
    }

    @McpTool(description = "Получить список последних деплоев из Jenkins.")
    public List<Map<String, Object>> getRecentDeploys(
        @McpToolParam(description = "Сервис") String service
    ) {
        // GET /job/{service}/api/json
        return List.of(Map.of("version", "v2.7.0", "status", "SUCCESS"));
    }

    @McpTool(description = "Создать тикет в Jira по результатам расследования.")
    public Map<String, Object> createJiraTicket(
        @McpToolParam(description = "Ключ проекта") String project,
        @McpToolParam(description = "Краткое описание") String summary,
        @McpToolParam(description = "Полный отчёт") String description
    ) {
        // POST /rest/api/2/issue
        return Map.of("ticketId", project + "-8872", "status", "created");
    }
}

// Сценарий 1: расследование проблемы с картой
@Component
public class ProductTools {

    @McpTool(description = "Получить список карт и заявок пользователя.")
    public List<Map<String, Object>> getUserCards(
        @McpToolParam(description = "ID пользователя") String userId
    ) {
        // SELECT * FROM mcp_api.v_card_orders WHERE user_id = ?
        return List.of(Map.of("application_id", "app_9912", "step", "4/5"));
    }

    @McpTool(description = "Получить шаги обработки заявки на карту.")
    public List<Map<String, Object>> getApplicationSteps(
        @McpToolParam(description = "ID заявки") String applicationId
    ) {
        // SELECT * FROM mcp_api.v_application_steps WHERE application_id = ?
        return List.of(Map.of("step", "COMPLIANCE_CHECK", "status", "FAILED"));
    }

    @McpTool(description = "Получить последние ошибки по пользователю из логов.")
    public List<Map<String, Object>> getRecentAppErrors(
        @McpToolParam(description = "ID пользователя") String userId,
        @McpToolParam(description = "Окно в минутах") Integer minutes
    ) {
        // POST /_search по userId и сервису
        return List.of(Map.of("error", "BackOfficeServiceClient timeout", "count", 3));
    }
}

@Component
public class BackOfficeTools {

    @McpTool(description = "Получить зависшие compliance-проверки для пользователя.")
    public List<Map<String, Object>> getPendingCompliance(
        @McpToolParam(description = "ID пользователя") String userId
    ) {
        // SELECT * FROM mcp_api.v_pending_compliance WHERE user_id = ?
        return List.of(Map.of("check_id", 887, "status", "PENDING"));
    }
}
```

Ключевой момент: `description` в аннотациях — это то, что читает LLM при выборе инструмента. Пишите на естественном языке, не жалейте деталей.

## Resources и Prompts: программная регистрация

Ресурсы и промпты статичны — объявляются через `@Bean`. Все три ресурса и оба промпта:

```java
@Configuration
public class McpResources {

    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> mcpResources() {
        return List.of(
            // Сценарий 1: схема шагов
            McpServerFeatures.SyncResourceSpecification.builder()
                .name("card-opening-flow")
                .description("Схема шагов оформления карты.")
                .mimeType("text/plain")
                .handler(request -> """
                    1. APPLICATION_CREATED → 2. KYC_CHECK → 3. SCORING
                    → 4. COMPLIANCE_CHECK (back-office) → 5. ACTIVATION
                    Если зависла на шаге 4: getApplicationSteps + getPendingCompliance
                    """)
                .build(),

            // Сценарий 2: схема БД
            McpServerFeatures.SyncResourceSpecification.builder()
                .name("payment-db-schema")
                .description("DDL таблиц transactions, settlements и индексы.")
                .mimeType("text/plain")
                .handler(request -> """
                    CREATE TABLE transactions (id, status, batch_id, ...);
                    CREATE INDEX idx_tx_batch_id ON transactions(batch_id);
                    -- ВАЖНО: нет индекса на (batch_id, status)
                    """)
                .build(),

            // Сценарий 2: runbook алёрта
            McpServerFeatures.SyncResourceSpecification.builder()
                .name("alert-runbook-payment")
                .description("Runbook для алёрта payment-high-latency.")
                .mimeType("text/plain")
                .handler(request -> """
                    1. getServiceMetrics → 2. getPodRestarts → 3. getBlockingSessions
                    → 4. getSlowQueries → 5. getRecentDeploys → 6. getServiceErrors
                    → 7. createJiraTicket
                    """)
                .build()
        );
    }
}
```

```java
@Configuration
public class McpPrompts {

    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> mcpPrompts() {
        return List.of(
            // Сценарий 1: расследование проблемы с картой
            McpServerFeatures.SyncPromptSpecification.builder()
                .name("investigate-card-opening")
                .description("Расследовать проблему с открытием карты")
                .addArgument("userId", "ID пользователя")
                .addArgument("backOfficeCardId", "ID back-office")
                .messages(messages -> List.of(
                    new Message(MessageRole.USER, """
                        Клиент {userId} не может открыть карту.
                        Back-office: {backOfficeCardId}.
                        Используй схему из card-opening-flow.
                        1. getUserCards → 2. getApplicationSteps
                        → 3. getRecentAppErrors → 4. getPendingCompliance
                        """)
                ))
                .build(),

            // Сценарий 2: автоматическое расследование алёрта
            McpServerFeatures.SyncPromptSpecification.builder()
                .name("auto-investigate-payment")
                .description("Авто-расследование алёрта high-latency")
                .addArgument("service", "Имя сервиса")
                .addArgument("metric", "Название метрики")
                .addArgument("threshold", "Пороговое значение")
                .addArgument("currentValue", "Текущее значение")
                .messages(messages -> List.of(
                    new Message(MessageRole.USER, """
                        🚨 {service} {metric} = {currentValue} (порог: {threshold})
                        Проведи расследование по runbook.
                        🔒 ПЕРВОПРИЧИНА: ...
                        📊 ДОКАЗАТЕЛЬСТВА: ...
                        ✅ ACTION PLAN: ...
                        🎫 Тикет в Jira: ...
                        """)
                ))
                .build()
        );
    }
}
```

Промпт не знает, какие tools доступны — он задаёт сценарий. LLM сама сопоставляет шаги с инструментами. Ресурсы дают ей контекст: схема БД помогает понять структуру таблиц, runbook — порядок действий, схема шагов заявки — на каком этапе какие tools нужны.

## Клиент: ChatClient + автоподключение tools

Одна строка — и все tools с сервера зарегистрированы в `ChatClient`:

```java
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  McpClientConnectionManager connectionManager) {
        return builder
            .defaultTools(connectionManager.getToolCallbacks())
            .build();
    }
}
```

Контроллер саппорта: `readResource` для контекста, `getPrompt` для сценария, `chatClient.prompt()` для запуска:

```java
@RestController
@RequestMapping("/support")
public class SupportController {

    private final ChatClient chatClient;
    private final McpClientConnectionManager connectionManager;

    @PostMapping("/ask")
    public Map<String, Object> ask(
        @RequestParam String userId,
        @RequestParam String backOfficeCardId
    ) {
        String flow = connectionManager.readResource("card-opening-flow");
        List<Message> prompt = connectionManager.getPrompt(
            "investigate-card-opening",
            Map.of("userId", userId, "backOfficeCardId", backOfficeCardId)
        );

        String response = chatClient.prompt()
            .system(s -> s.text(flow))
            .messages(prompt)
            .call()
            .content();

        return Map.of("userId", userId, "response", response);
    }
}
```

Три шага: ресурс, промпт, `ChatClient`. LLM сама решает, какие tools вызвать.

## До и после

Без MCP LLM гадает. С MCP — оперирует фактами:

```
Без MCP:
«Я не имею доступа к вашей базе данных или логам.
 Возможные причины: сбой, верификация, ограничения по карте.»

С MCP:
«Заявка app_9912 зависла на шаге COMPLIANCE_CHECK.
 Три таймаута BackOfficeServiceClient. Проверка #887 — PENDING.
 Дежурная команда уже поднята. Карта выпустится после восстановления.»
```
