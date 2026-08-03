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

## Ссылки

- [Спецификация MCP](https://spec.modelcontextprotocol.io)
- [Spring AI MCP Reference](https://docs.spring.io/spring-ai/reference/2.0/api/mcp/mcp-annotations-server.html)
- **Исходный код:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server), [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client)
- [MCP Hub](https://github.com/modelcontextprotocol/servers) — каталог готовых серверов
