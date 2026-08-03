# Примеры реальных задач

Возьмём банковский ритейл: три сервиса (`payment-service`, `product-service`, `back-office-service`), каждый со своей БД, общаются через API. Два сценария на одном MCP-сервере.

## Сценарий 1: Саппорт расследует проблему с картой

Клиент звонит: «Пытаюсь открыть виртуальную карту, три раза нажал — ошибка». Саппорт открывает чат. Без MCP: DBeaver → копипаст в чат → Grafana → скриншот → снова чат. С MCP: один вопрос.

| Тип | Название | Источник | Что делает |
|---|---|---|---|
| **Tool** | `getUserCards(userId)` | `product_db` | Активные карты и заявки пользователя |
| **Tool** | `getApplicationSteps(applicationId)` | `product_db` | На каком шаге заявка упала |
| **Tool** | `getRecentAppErrors(userId, minutes)` | OpenSearch | Ошибки в логах по пользователю |
| **Tool** | `getPendingCompliance(userId)` | `backoffice_db` | Зависшие compliance-проверки |
| **Resource** | `card-opening-flow` | — | Схема: заявка → KYC → скоринг → compliance → активация |
| **Prompt** | `investigate-card-opening` | — | Структурирует диалог для саппорта |

**Ход расследования:**

1. Саппорт выбирает prompt `investigate-card-opening` с параметрами `userId=user_45678`, `backOfficeCardId=bo_887`. Хост подгружает ресурс `card-opening-flow`.
2. LLM вызывает `getUserCards` → в БД нет активных карт, заявка `app_9912` на шаге 4/5.
3. LLM вызывает `getApplicationSteps` → шаг `COMPLIANCE_CHECK` упал с таймаутом.
4. LLM вызывает `getRecentAppErrors` + `getPendingCompliance` → 3 таймаута BackOfficeServiceClient, проверка #887 висит PENDING.
5. LLM отвечает: «Заявка зависла на compliance. Дежурная команда уже поднята. Клиенту ничего делать не нужно.»

Саппорт задал вопрос — LLM прошла цепочку: карты → шаги → ошибки → compliance.

## Сценарий 2: Алёрт — система сама пошла расследовать

Ночь. Alertmanager: `payment-service p99_latency = 5.2s` (порог 500ms). Хост получает вебхук и запускает расследование.

| Тип | Название | Источник | Что делает |
|---|---|---|---|
| **Tool** | `getServiceMetrics(service, metric, minutes)` | Prometheus | p99_latency, db_connections_active |
| **Tool** | `getPodRestarts(service, minutes)` | Kubernetes | Рестарты подов, OOMKilled |
| **Tool** | `getBlockingSessions()` | `payments_db` | Кто кого блокирует |
| **Tool** | `getSlowQueries(database, minutes, limit)` | `payments_db` | Топ тяжёлых запросов |
| **Tool** | `getServiceErrors(service, minutes)` | OpenSearch | Ошибки в логах |
| **Tool** | `getRecentDeploys(service)` | Jenkins | Последние деплои |
| **Tool** | `createJiraTicket(project, summary, description)` | Jira | Создать тикет |
| **Resource** | `payment-db-schema`, `alert-runbook-payment` | — | DDL таблиц + runbook алёрта |
| **Prompt** | `auto-investigate-payment` | — | Структурирует авто-расследование |

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
