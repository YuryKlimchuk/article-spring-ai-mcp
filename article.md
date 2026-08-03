# Spring AI + MCP: Giving LLMs Safe Access to Your Systems

> **TL;DR**
>
> - **Проблема:** LLM изолирована от ваших систем — она не может проверить тормоза в БД, вызвать API или прочитать логи.
> - **Решение:** Model Context Protocol (MCP) даёт LLM контролируемый доступ к инструментам, данным и сценариям. Один сервер — любые клиенты.
> - **Реализация:** Spring AI 2.0 сводит всё к трём аннотациям: `@McpTool`, `@McpResource`, `@McpPrompt`. 15 строк кода на инструмент.
> - **Результат:** саппорт расследует инциденты за минуту вместо часа. Ночные алёрты разбираются автоматически. Код не дублируется между клиентами.

> 📦 **Source code:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server) · [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client)

## Введение

Вы просите LLM рассказать о новой версии Spring — она отвечает, что такой версии нет. Просите проверить тормоза на вашей БД — она не умеет. Причина одна: LLM изолирована от внешнего мира. Ей нужен контролируемый способ вызывать функции и получать актуальные данные.

Раньше это решали function calling'ом — механизмом, где описание функций встраивается прямо в запрос к LLM. Проблема в том, что каждый провайдер (OpenAI, Anthropic, Google) реализовывал его по-своему, а инструменты были жёстко привязаны к конкретному приложению. Переиспользовать их в другом клиенте без переписывания было невозможно.

MCP решает эту проблему на уровне протокола: инструменты живут на сервере, а не привязаны к хосту и провайдеру. Один сервер — любые MCP-совместимые клиенты. Именно поэтому индустрия движется к MCP: инструменты становятся ресурсом, а не фичей конкретного провайдера.

**Model Context Protocol (MCP)** придумала Anthropic в конце 2024 года. Сегодня протокол поддерживают Zed IDE, Sourcegraph и растущее open-source сообщество. Это клиент-серверный протокол поверх JSON-RPC: сервер предоставляет инструменты, ресурсы и промпты, клиент — любое MCP-совместимое приложение.

**Spring AI 2.0** делает интеграцию простой: аннотации на методах, автоконфигурация на транспорте, `ChatClient` на стороне клиента. Мы разберём архитектуру MCP и три ключевых концепта — Tool, Resource и Prompt. Затем — работающий прототип на Spring Boot и защиту PII через VIEW в БД.

---

# Как работает MCP

## Архитектура: Host, Client, Server

MCP построен на трёхкомпонентной архитектуре **host-client-server**:

```
┌─────────────────────────────────────────────────────┐
│ Host (Claude Desktop, IDE, ваше приложение)         │
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

- **Host** — приложение с LLM (Claude Desktop, IDE, ваш чат). Содержит MCP-клиент.
- **MCP Client** — подключается к серверу, отправляет JSON-RPC, возвращает результаты хосту.
- **MCP Server** — Spring Boot-сервис с инструментами, ресурсами и промптами. Не знает, кто подключился.

## Три примитива и кто ими управляет

Главное различие между Tool, Resource и Prompt — **кто** принимает решение об их использовании:

- **Tool** — действие (SQL, API, запись). Вызывает **LLM**: модель получает список инструментов и решает, когда какой вызвать.
- **Resource** — данные (схема БД, документация). Загружает **хост**: сервер объявляет ресурсы, клиент решает когда подгрузить их в контекст.
- **Prompt** — шаблон сообщения с параметрами. Выбирает **пользователь** через `/` или меню.

> Resources и Prompts — необязательные возможности MCP. На практике большинство серверов обходятся только Tools.

## Транспорт: stdio и Streamable HTTP

MCP работает поверх JSON-RPC, а физически сообщения передаются одним из двух способов:

**stdio** — локально: хост запускает jar как подпроцесс, общение через stdin/stdout. Аутентификация не нужна.

**Streamable HTTP** (сменил SSE в 2025) — удалённо: `POST /mcp` по HTTP, аутентификация через Origin-валидацию или OAuth 2.1.

**⚠️ Важно для stdio**: сервер пишет в stdout только JSON-RPC. `System.out.println`, баннер Spring Boot, любой другой вывод ломают протокол. Лечится:

```yaml
spring.main.banner-mode: off
logging.file.name: /dev/stderr   # Linux. Windows: logging.file.name: NUL или путь к файлу
```

## Жизненный цикл

1. **Initialize** — клиент и сервер обмениваются capabilities: «У меня есть tools, resources, prompts».
2. **tools/list** — клиент запрашивает список инструментов, Spring AI регистрирует их в `ChatClient`.
3. **tools/call** — LLM выбрала инструмент → клиент выполняет вызов на сервере → результат возвращается в LLM.
4. **Ответ** — LLM обрабатывает результат и формирует финальный ответ.

## Практические ограничения

MCP — мощный, но не бесплатный. Что учитывать в production:

- **Задержки.** Цепочка из 7 tool-вызовов добавляет 1–3 секунды. Для real-time — прямое API.
- **Аутентификация.** Streamable HTTP требует OAuth 2.1 или API-ключей. STDIO — только локально.
- **Обработка ошибок.** Если tool упал — LLM может повторить вызов. Нужен таймаут и лимит попыток.
- **Права доступа.** LLM видит все зарегистрированные tools. Фильтруйте административные через `McpToolFilter`.
- **Идемпотентность.** `createJiraTicket` должен быть защищён от двойного вызова.
- **Аудит.** Логируйте все вызовы на стороне сервера — не полагайтесь на LLM.

Теперь перейдём к практике: два сквозных сценария из банковского ритейла.

> **Документация:** [спецификация MCP](https://spec.modelcontextprotocol.io) — полное описание протокола; [Spring AI MCP reference](https://docs.spring.io/spring-ai/reference/2.0/api/mcp/mcp-annotations-server.html) — как подключить сервер к Spring Boot.

---

# Примеры реальных задач

Возьмём банковский ритейл: три сервиса (`payment-service`, `product-service`, `back-office-service`), каждый со своей БД, общаются через API. Два сценария на одном MCP-сервере.

## Сценарий 1: Саппорт расследует проблему с картой

Клиент звонит: «Пытаюсь открыть виртуальную карту, три раза нажал — ошибка». Саппорт открывает чат. Без MCP: DBeaver → копипаст в чат → Grafana → скриншот → снова чат. С MCP: один вопрос.

**Состав сервера:** `getUserCards`, `getApplicationSteps`, `getRecentAppErrors`, `getPendingCompliance` — tools; `card-opening-flow` — resource; `investigate-card-opening` — prompt.

**Ход расследования:**

1. Саппорт выбирает prompt `investigate-card-opening` с параметрами `userId=user_45678`, `backOfficeCardId=bo_887`. Хост подгружает ресурс `card-opening-flow`.
2. LLM вызывает `getUserCards` → в БД нет активных карт, заявка `app_9912` на шаге 4/5.
3. LLM вызывает `getApplicationSteps` → шаг `COMPLIANCE_CHECK` упал с таймаутом.
4. LLM вызывает `getRecentAppErrors` + `getPendingCompliance` → 3 таймаута BackOfficeServiceClient, проверка #887 висит PENDING.
5. LLM отвечает: «Заявка зависла на compliance. Дежурная команда уже поднята. Клиенту ничего делать не нужно.»

Саппорт задал вопрос — LLM прошла цепочку: карты → шаги → ошибки → compliance.

## Сценарий 2: Алёрт — система сама пошла расследовать

Ночь. Alertmanager: `payment-service p99_latency = 5.2s` (порог 500ms). Хост получает вебхук и запускает расследование.

**Состав сервера:** `getServiceMetrics` (Prometheus), `getPodRestarts` (K8s), `getBlockingSessions`, `getSlowQueries` (БД), `getServiceErrors` (OpenSearch), `getRecentDeploys` (Jenkins), `createJiraTicket` (Jira) — tools; `payment-db-schema`, `alert-runbook-payment` — resources; `auto-investigate-payment` — prompt.

**Ход расследования:**

1. Alertmanager отправляет вебхук. Хост подгружает ресурсы `payment-db-schema` и `alert-runbook`, применяет prompt `auto-investigate-payment`.
2. LLM вызывает `getServiceMetrics` → p99_latency = 5.2s (рост с 200ms).
3. LLM вызывает `getPodRestarts` → 3 рестарта с OOMKilled в 00:13.
4. LLM вызывает `getBlockingSessions` → pid 8732 держит RowExclusiveLock, 55 сессий ждут.
5. LLM вызывает `getSlowQueries` → `UPDATE transactions SET status='SETTLED'` — mean_time 4.8s.
6. LLM вызывает `getServiceErrors` → 450× «could not obtain lock».
7. LLM вызывает `createJiraTicket` → тикет PAY-8872 создан.

LLM выдала диагноз: OOM → рестарты → connection pool исчерпан → блокировка pid 8732. Action plan: `pg_terminate_backend(8732)`, memory limit 4Gi, индекс на `transactions(batch_id)`, connection pool 150.

## Безопасность: почему LLM не видит сырые данные

> Прежде чем перейти к реализации — важное архитектурное решение.

Названия вроде `mcp_api.v_blocking_sessions` — не опечатка, а архитектурное решение. Схема `mcp_api` с VIEW, доступ под ролью `mcp_readonly` только на SELECT:

```sql
-- Роль только на чтение, public-схема закрыта
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

Аналогичные VIEW в `product_db` и `backoffice_db` — с маскировкой PII и ограничением по датам.

**Почему это важно именно для MCP.** Обычный REST API тоже маскирует PII. Но MCP добавляет уникальный риск: LLM сама решает, какие Tool вызвать и с какими параметрами. Защита на уровне VIEW означает: что бы LLM ни придумала — она физически не может прочитать `card_number` или удалить строку. Не «надеемся, что LLM будет хорошей», а «спроектировали так, что у LLM нет выбора».

---

Оба сценария работают на одном сервере — меняется только инициатор взаимодействия. Эти же Tool и Resource мы сейчас реализуем в коде: каждый из них — 10–15 строк на Spring Boot.

---

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

---

# Итоги

Мы начали с LLM, которая не знает о выходе новой версии Spring. Закончили архитектурой, где LLM сама расследует алёрты и выдаёт action plan. MCP-сервер на Spring Boot обслуживает и саппорта, и автоматику.

## Когда MCP не нужен

MCP не заменяет REST API, gRPC или Kafka — он решает другую задачу: даёт LLM стандартизированный доступ к инструментам, ресурсам и промптам. Три случая, где MCP только добавит сложности:

- **Сервис уже спроектирован с хорошим REST-контрактом.** Если клиенты — не LLM, а другие микросервисы или фронтенд, MCP-прослойка избыточна.
- **Высокочастотные вызовы.** Каждый tool call добавляет 50–200 мс сетевых накладных. Для real-time потоков — прямое API, не цепочка tool-вызовов.
- **Критические мутации без подтверждения.** LLM не должна самостоятельно решать, удалять запись или менять лимит. Tools с побочными эффектами требуют человеческого подтверждения — MCP для диагностики, не для `DELETE FROM users`.

## Ключевые выводы

**Spring AI 2.0 — зрелый инструмент для production-интеграций.** `@McpTool` на методе — и он доступен LLM. Клиент подключается одной строкой: `.defaultTools(toolCallbackProvider.getToolCallbacks())`. STDIO и Streamable HTTP из коробки.

**Безопасность на уровне БД — не опция, а архитектура.** Роль `mcp_readonly` имеет SELECT только на VIEW в схеме `mcp_api`. LLM физически не может прочитать PII и выполнить DML.

**Tool, Resource, Prompt — три точки входа.** LLM инициирует вызов Tool, хост загружает Resource, пользователь выбирает Prompt. Жёсткое разделение ответственности.

---

Главный вывод: MCP превращает LLM из изолированного советчика в агента, который работает с живыми данными ваших систем. Не «я предполагаю», а «я проверил — вот факты и план действий». Spring AI 2.0 делает это доступным для любого Java-разработчика: три аннотации и автоконфигурация — всё, что нужно для production.

## Что дальше

1. Поднимите локальный MCP-сервер — код из статьи работает сразу после `git clone`.
2. Подключите его к Claude Desktop или Cursor — все 11 инструментов будут доступны в чате.
3. Добавьте первый `@McpTool` для своей системы — 15 строк кода, и LLM сможет с ней взаимодействовать.
4. Переносите реальные сценарии: диагностика инцидентов, авто-расследования, доступ к документации.

## Ссылки

- [Спецификация MCP](https://spec.modelcontextprotocol.io)
- [Spring AI MCP Reference](https://docs.spring.io/spring-ai/reference/2.0/api/mcp/mcp-annotations-server.html)
- **Исходный код:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server), [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client)
- [MCP Hub](https://github.com/modelcontextprotocol/servers) — каталог готовых серверов
