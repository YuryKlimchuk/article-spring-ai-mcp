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
