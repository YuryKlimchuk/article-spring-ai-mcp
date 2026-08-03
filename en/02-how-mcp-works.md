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
