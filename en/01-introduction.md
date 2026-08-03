# Spring AI + MCP: Giving LLMs Safe Access to Your Systems

**TL;DR**

- **Problem:** LLMs are isolated from your systems — they can't check database slowdowns, call an API, or read logs.
- **Solution:** The Model Context Protocol (MCP) gives LLMs controlled access to tools, data, and workflows. One server, any client.
- **Implementation:** Spring AI 2.0 boils it down to three annotations: `@McpTool`, `@McpResource`, `@McpPrompt`. Fifteen lines of code per tool.
- **Result:** Support investigates incidents in a minute instead of an hour. Night-time alerts are triaged automatically. Zero code duplication across clients.

📦 **Source code:** [server](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/server) · [client](https://github.com/YuryKlimchuk/article-spring-ai-mcp/tree/main/client)

## Introduction

You ask an LLM about the latest Spring release — it tells you that version doesn't exist. You ask it to check why your database is slow — it can't. The reason is simple: an LLM is isolated from the outside world. It needs a controlled way to call functions and fetch up-to-date data.

Previously, this was solved with function calling — a mechanism where function descriptions are embedded directly into the LLM request. The problem? Every provider (OpenAI, Anthropic, Google) implemented it differently, and tools were tightly coupled to a specific application. Reusing them in another client without rewriting was impossible.

MCP solves this at the protocol level: tools live on a server, not tied to a host or provider. One server — any MCP-compatible client. That's why the industry is moving toward MCP: tools become a resource, not a feature of a particular provider.

**Model Context Protocol (MCP)** was created by Anthropic in late 2024. Today the protocol is supported by Zed IDE, Sourcegraph, and a growing open-source community. It's a client-server protocol built on JSON-RPC: the server provides tools, resources, and prompts; the client is any MCP-compatible application.

**Spring AI 2.0** makes integration straightforward: annotations on methods, auto-configuration for transport, `ChatClient` on the client side. We'll walk through the MCP architecture and its three key concepts — Tool, Resource, and Prompt. Then we'll build a working Spring Boot prototype and cover PII protection using a database VIEW.
