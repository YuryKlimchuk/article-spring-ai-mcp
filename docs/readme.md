# Spring AI как инструмент для интеграции LLM в ваши системы
## Часть 1 — Model Context Protocol

## План

### 1. Введение (~250 слов) — проблема и решение

- **Для кого**: Статья для Java/Spring-разработчиков, которые хотят интегрировать LLM с реальными данными и API.
- **Проблема — изоляция + безопасность**: LLM обучена на данных до определенного момента. Без связи с внешним миром она не может обратиться к БД, вызвать API, прочитать файл. Дать ей прямой доступ (ключи, пароли) — опасно, не давать — бесполезно. Нужен контролируемый мост.
- **Проблема — дублирование**: инструменты всегда жили внутри конкретного приложения. Написал интеграцию с PostgreSQL для своего бэкенда — для Claude Desktop пиши заново, для Copilot — третий раз. Переиспользования нет.
- **Как решали раньше**: function calling от провайдеров встраивал описание инструментов прямо в запрос к LLM, но инструменты были «прибиты» к хосту.
- **Решение — MCP**: открытый стандарт от Anthropic. Один сервер с инструментами — любые клиенты. LLM видит только разрешённые операции, а не сырой доступ к БД.
- **Роль Spring AI 2.0**: автоконфигурация, `@McpTool`/`@McpToolParam` для действий, `@McpResource` и `@McpPrompt` для ресурсов и промптов.
- **Анонс**: разберем архитектуру MCP и реализацию сервера на Spring Boot + Spring AI 2.0.

### 2. Как работает MCP (500–600 слов) — архитектура + схема

- **Клиент-серверная архитектура**:
  - **Host** — приложение с AI (Claude Desktop, IDE). Содержит MCP Client.
  - **MCP Client** — инициирует соединение, отправляет запросы, получает ответы.
  - **MCP Server** — Spring Boot приложение, предоставляет Tools, Resources, Prompts.
- **Три концепта — кто что инициирует**:
    - **Tool** — вызывается **LLM**. ChatClient передаёт список инструментов в каждом запросе → LLM решает: вызвать `getSlowQueries` или ответить сама.
    - **Resource** — загружается **host/кодом**. LLM может знать о существовании ресурсов (через `resources/list`), но не вызывает их — решение о загрузке в контекст принимает приложение.
    - **Prompt** — выбирается **пользователем** (slash-команды, меню). Сервер возвращает массив `messages[]` с ролями user/assistant, host вставляет в чат. Принципиально отличается от Tools.
- **Транспорт**: JSON-RPC поверх:
    - **stdio** — локально (Claude Desktop запускает jar, общение через stdin/stdout)
    - **Streamable HTTP** — удалённо (`POST /mcp`, заменил устаревший SSE в спецификации 2025)
    - ⚠️ **Gotcha**: stdio-сервер не должен писать в stdout ничего кроме JSON-RPC. `System.out.println` ломает протокол → `spring.main.banner-mode=off`, логи в stderr.
- **Жизненный цикл** (mermaid sequence diagram):
    - `initialize` → Client и Server обмениваются capabilities
    - `initialized` → Client подтверждает готовность (notification)
    - `tools/list` → Client запрашивает инструменты (отдельный запрос)
    - Spring AI регистрирует tools как tool callbacks в `ChatClient`
    - Каждый запрос к LLM включает описание инструментов → LLM решает
    - `tools/call` → MCP-клиент выполняет → результат в LLM → финальный ответ

### 3. Примеры реальных задач (800 слов) — два сквозных сценария

**Контекст:** банковский ретейл, три микросервиса:

| Сервис | БД | Ключевые таблицы | Что делает |
|---|---|---|---|
| `payment-service` | `payments_db` | `transactions`, `settlements`, `payment_methods` | Платёжные операции, эквайринг |
| `product-service` | `product_db` | `card_orders`, `application_steps` | Жизненный цикл продукта: заявки на карты, выпуск |
| `back-office-service` | `backoffice_db` | `compliance_checks`, `approval_requests` | Проверки compliance, одобрения, ручные решения |

Метрики — Prometheus + Grafana, логи — OpenSearch, деплои — Jenkins.

#### Сценарий 1 (ручной): Саппорт — клиент не может открыть карту

**Проблема:** клиент звонит в банк: «Пытаюсь открыть виртуальную карту в приложении, три раза нажал, каждый раз ошибка на последнем шаге». Саппорт открывает чат и расследует сам, без разработчиков.

| Тип | Название | Источник | Что делает |
|---|---|---|---|
| **Tool** | `getUserCards(userId)` | `product_db` | Есть ли у клиента активная карта: `SELECT * FROM mcp_api.v_card_orders WHERE user_id = ? AND status = 'ACTIVE'` |
| **Tool** | `getApplicationSteps(applicationId)` | `product_db` | На каком шаге заявка упала: `SELECT step, status, error_code FROM mcp_api.v_application_steps` |
| **Tool** | `getRecentAppErrors(userId, minutes)` | OpenSearch | Ошибки в логах по userId и сервису |
| **Tool** | `getPendingCompliance(userId)` | `backoffice_db` | Зависшие compliance-проверки: `SELECT * FROM mcp_api.v_pending_compliance WHERE user_id = ?` |
| **Resource** | `card-opening-flow` | Файл на сервере | Схема шагов: заявка → KYC → скоринг → compliance → активация |
| **Prompt** | `investigate-card-opening` | — | «Клиент {userId} не может открыть карту (back-office: {backOfficeCardId}). Проверь статус заявки по схеме из ресурса, найди ошибки, объясни причину.» |

**Диалог:** саппорт сообщает userId → LLM вызывает `getUserCards` (нет активных карт, заявка на шаге compliance) → `getApplicationSteps` (compliance check: FAILED, таймаут) → `getRecentAppErrors` (три таймаута BackOfficeServiceClient) → `getPendingCompliance` (проверка #887 висит PENDING). LLM отвечает: «Заявка зависла на проверке compliance — back-office не отвечает. Дежурная команда уже поднята по алёрту, после восстановления заявка дообработается автоматически. Клиенту ничего делать не нужно.»

**Что демонстрирует:** Tool вызываются LLM для получения живых данных из БД и логов. Resource (схема шагов) загружается хостом до начала диалога — LLM понимает контекст процесса. Prompt структурирует диалог для конкретной задачи саппорта.

#### Сценарий 2 (автоматический): Алёрт — платежи встали

**Проблема:** ночь. Alertmanager: `payment-service p99_latency > 5s`. Никто не жмёт кнопок — хост получает вебхук и запускает авто-расследование.

| Тип | Название | Источник | Что делает |
|---|---|---|---|
| **Tool** | `getSlowQueries(service, minutes, limit)` | `payments_db`, `pg_stat_statements` | Топ тяжёлых запросов через `mcp_api.v_slow_queries` |
| **Tool** | `getBlockingSessions()` | `payments_db`, `pg_stat_activity` | Кто кого блокирует через `mcp_api.v_blocking_sessions` |
| **Tool** | `getServiceMetrics(service, metric, minutes)` | Prometheus | `p99_latency`, `db_connections_active`, `http_errors` |
| **Tool** | `getRecentDeploys(service)` | Jenkins | Последние деплои: версия, автор, время |
| **Tool** | `getServiceErrors(service, minutes)` | OpenSearch | Топ ошибок в логах |
| **Tool** | `createJiraTicket(project, summary, description)` | Jira | Создать тикет: `POST /rest/api/2/issue` |
| **Resource** | `payment-db-schema` | `payments_db`, `information_schema` | DDL таблиц `transactions`, `settlements`, индексы |
| **Resource** | `alert-runbook-payment` | Файл на сервере | Runbook для алёрта `payment-high-latency` |
| **Prompt** | `auto-investigate-payment` | — | «Сработал алёрт. Проверь метрики, БД, K8s, логи, деплои. Найди первопричину. Предложи action. Создай тикет в Jira. Формат: диагноз → доказательства → решение.» |

**Поток:** вебхук → хост активирует prompt + подгружает Resources (DDL, runbook) → LLM вызывает `getServiceMetrics` (рост latency до 5.2s) → `getPodRestarts` (3 рестарта, OOMKilled) → `getServiceMetrics` (connections: 97/100) → `getBlockingSessions` (pid 8732 держит RowExclusiveLock) → `getSlowQueries` (batch settlement, mean_time 4.8s) → `getServiceErrors` (450× "could not obtain lock") → `createJiraTicket` (тикет PAY-8872). LLM выдаёт: «🔒 OOM → рестарты → connection pool → блокировка. Предлагаю: (1) `pg_terminate_backend(8732)`, (2) `kubectl set resources --limits=memory=4Gi`, (3) JVM heap -Xms3g -Xmx3g, (4) индекс, (5) увеличить pool.»

**Что демонстрирует:** полная автономия — от алёрта до диагноза, action plan и тикета в Jira без участия человека. LLM выступает агентом: сама решает, какие Tool вызвать и в каком порядке. Resource дают контекст (DDL + runbook), Prompt структурирует ответ.

#### Безопасность: отдельная схема mcp_api, только VIEW, только SELECT

Оба сценария используют единый подход к безопасности на уровне БД:

```
Пользователь БД:  mcp_readonly
Схема:            mcp_api (отдельная от public)
Права:            только SELECT на VIEW в схеме mcp_api
                  REVOKE ALL на public, pg_catalog
                  CONNECTION LIMIT = 5
```

**Что это даёт:**
- **LLM не видит PII** — VIEW маскирует чувствительные колонки (номер карты, email, IP)
- **LLM не может менять данные** — роль `mcp_readonly` не имеет прав на INSERT/UPDATE/DELETE
- **LLM не видит чужие таблицы** — `REVOKE ALL ON SCHEMA public`, доступ только к `mcp_api.*`
- **MCP-сервер взломан → ущерб ограничен** — даже скомпрометированный сервер не может удалить данные
- **Аудит** — все запросы от `mcp_readonly` логируются стандартными средствами PostgreSQL

Пример VIEW для сценария с блокировками:

```sql
CREATE SCHEMA mcp_api;
CREATE ROLE mcp_readonly WITH LOGIN PASSWORD '***' CONNECTION LIMIT 5;
GRANT USAGE ON SCHEMA mcp_api TO mcp_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA mcp_api TO mcp_readonly;
REVOKE ALL ON SCHEMA public FROM mcp_readonly;

-- VIEW: блокировки — только своя БД, обрезанные запросы
CREATE VIEW mcp_api.v_blocking_sessions AS
SELECT
    blocked.pid              AS blocked_pid,
    LEFT(blocked.query, 200) AS blocked_query_short,
    blocked.wait_event_type  AS blocked_wait_type,
    blocking.pid             AS blocking_pid,
    LEFT(blocking.query, 200) AS blocking_query_short,
    (blocked.query_start - blocking.query_start) AS blocking_duration
FROM pg_stat_activity blocked
JOIN pg_stat_activity blocking
    ON pg_blocking_pids(blocked.pid) @> ARRAY[blocking.pid]
WHERE blocked.pid != blocking.pid
  AND blocked.wait_event_type = 'Lock'
  AND blocked.datname = 'payments_db';

-- VIEW: транзакции — без PII, только 30 дней
CREATE VIEW mcp_api.v_transactions AS
SELECT
    id, status, amount, merchant_category,
    LEFT(merchant_name, 3) || '***' AS merchant_masked,
    created_at, batch_id, settling_status, error_code
FROM public.transactions
WHERE created_at > NOW() - INTERVAL '30 days';
```

Эта модель — не просто «лучшая практика», а необходимость: LLM не должна иметь доступ к сырым данным клиентов банка. MCP-сервер + VIEW решают эту проблему на уровне архитектуры.

### 4. Реализация на Spring Boot (900 слов) — код + конфигурация

- **Структура проекта**:
  - Maven multi-module: `mcp-server` (сервер с Tools/Resources/Prompts) + `mcp-client` (хост с ChatClient)
  - Три PostgreSQL БД: `payments_db`, `product_db`, `backoffice_db` — в Docker Compose
  - В каждой БД — схема `mcp_api` с VIEW, роль `mcp_readonly`, тестовые данные в `init.sql`
- **Зависимости (pom.xml)**:
  - spring-boot-starter-parent 4.1.x
  - spring-ai-starter-mcp-server-webmvc 2.0.x
  - spring-ai-starter-mcp-client 2.0.x
  - spring-boot-starter-jdbc + postgresql
  - spring-boot-starter-web (для REST-контроллера и Streamable HTTP)
- **Конфигурация application.yml (MCP Server)**:
    - MCP server: протокол `STREAMABLE`, порт
    - Три datasource для `payments_db`, `product_db`, `backoffice_db`
    - Пользователь БД: `mcp_readonly` (только SELECT по схеме `mcp_api`)
    - `logging.level` для демонстрации цепочки вызовов
- **Код MCP-сервера** — все три концепта:
    - **Tool** — `@McpTool` + `@McpToolParam` на сервисах. Пример: `getBlockingSessions()` выполняет `SELECT * FROM mcp_api.v_blocking_sessions`, `getSlowQueries(service, limit)` — `SELECT * FROM mcp_api.v_slow_queries`
    - **Resource** — `@McpResource` на методе. Пример: `card-opening-flow` возвращает текстовую схему, `payment-db-schema` — DDL таблиц
    - **Prompt** — `@McpPrompt` на методе, принимает `McpPromptRequest`, возвращает `Message[]` с параметрами. Пример: `investigate-card-opening` для саппорта, `auto-investigate-payment` для алёрта
- **Конфигурация MCP-клиента**:
    - Адрес MCP-сервера в application.yml
    - Автоконфигурация регистрирует tools как tool callbacks в `ChatClient`
    - REST-контроллер с двумя endpoint-ами: `/support/ask` (сценарий 1, выбор prompt вручную) и `/alert/webhook` (сценарий 2, авто-расследование)
- **Демонстрация "до и после" на сценарии 2**:
  - `curl .../alert/ask?mcp=false` → *«Я не имею доступа к вашей базе данных, но могу предположить...»* (hallucination или общие слова)
  - `curl .../alert/ask?mcp=true` → полный диагноз: блокировка pid 8732, 55 ожидающих сессий, запрос-виновник, action plan
  - Разница в одном окне: без MCP — гадание, с MCP — факты и конкретный план действий.
- **GitHub-репозиторий** с полным кодом и Docker Compose (ссылка).

### 5. Итоги (~250 слов) — выводы + что дальше

- **Выводы**:
    - MCP убирает vendor-lock: один сервер с инструментами обслуживает и саппорта, и автоматику
    - Spring AI делает интеграцию тривиальной: `@McpTool` для действий, `@McpResource` и `@McpPrompt` для ресурсов и промптов
    - MCP-сервер — контролируемый шлюз: LLM видит только разрешённые операции через `mcp_api` VIEW
    - Безопасность на уровне БД (отдельная схема, только SELECT) — не опционально, а архитектурная необходимость для продакшена
- **Безопасность**: MCP-сервер — обычное Spring-приложение, дополнительно защищается через Spring Security (OAuth2, JWT). Но основной рубеж — на уровне PostgreSQL: `mcp_readonly` с доступом только к `mcp_api.*` VIEW.
- **Что дальше**:
    - MCP Hub — каталог готовых серверов для популярных систем
    - OAuth 2.1 в спецификации MCP для удалённых подключений
    - Мониторинг, rate limiting, аудит вызовов MCP
    - Ссылки: спецификация MCP, документация Spring AI MCP, репозиторий с кодом

---

## Структура файлов
- `ru/` — статьи на русском языке (каждый пункт в отдельном `.md` файле)
- `eng/` — переводы на английском языке
