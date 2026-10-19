# ADR 0001 — MCP-first external interface, thin Kotlin CLI

Status: **Accepted for driven mode** (the primary interface is now the on-device agent, ADR 0005; the local HTTP API is implemented, the `/mcp` endpoint is not built) · Date: 2026-09-25

## Context

Hermes is the first driving agent. Its config already registers HTTP MCP servers under `mcp_servers` (e.g. `qmd: url: http://localhost:8686/mcp`). Claude Code also speaks MCP. A bespoke CLI plus prose `skill.md` would make each agent learn our command vocabulary from text.

The harness also needs a standalone mode (built-in agent loop against an OpenAI-compatible LLM), and a way to debug by hand.

## Decision

1. The device app runs an HTTP server exposing:
   - `/mcp` — MCP streamable HTTP, one tool per action (primary interface).
   - `/api/*` — plain JSON mirroring the same tools (for `curl` and the CLI).
   - `/ws` — event stream.
2. `assistdroid-cli` is a **Kotlin/JVM** program sharing a `protocol` module (request/response types, error codes) with the Android app. It is a thin client of `/api/*` plus `agent run` for standalone mode.
3. The standalone agent loop is a client of the same automation core / API, never a parallel implementation.
4. Server stack candidate: Ktor + the official MCP Kotlin SDK. **Verify** it runs on Android (minSdk 26) in the spike before committing.

## Consequences

- Hermes integration = one `mcp_servers` entry; tool schemas are typed and self-describing.
- `skill.md` shrinks to workflow guidance; it no longer duplicates command syntax.
- A shared `protocol` module keeps CLI, app and tests consistent.
- Risk: MCP-on-Android library maturity. Fallback: implement the small subset of MCP JSON-RPC we need by hand over Ktor.

## Alternatives considered

- **CLI + skill.md only:** works with any agent that can shell out, but untyped and more tokens/brittleness. Kept as a secondary path via the CLI.
- **Go/Rust CLI:** rejected; a Kotlin CLI reuses the protocol module and the owner's primary language.
- **Adopt an existing Android MCP project:** pending the prior-art review in requirements §1.2.
