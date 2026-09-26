# ADR 0002 — Security model

Status: **Proposed** · Date: 2026-09-25

## Context

An AccessibilityService that accepts network commands can read every screen and act as the user: banking apps, 2FA codes, messages. Whoever reaches the port controls the phone. Earlier drafts had an optional token and `0.0.0.0` binding, contradicting the privacy requirements.

v1 is personal use; open-sourcing may follow, so defaults must already be safe.

## Decision

1. **Mandatory bearer token**, generated on first run (random, ≥128 bits), shown in the app, rotatable. Every HTTP, MCP and WebSocket request must present it. Empty/default tokens are not allowed.
2. **Bind `127.0.0.1` by default.** Emulator/USB access uses `adb forward tcp:8427 tcp:8427`. Binding to LAN/Tailscale is an explicit setting. Plain HTTP is acceptable only over localhost or a Tailscale (WireGuard) tunnel; anything else requires TLS.
3. **App denylist** (banks, password managers, authenticators, the harness itself), refused by default and editable only in the app UI, not over the API.
4. **Sensitive content:** password fields are returned as `[password]`; `FLAG_SECURE` windows are reported as blind rather than captured.
5. **Audit log** of every action (time, caller, tool, target package) stored locally and viewable in the app.
6. **Kill switch** in the app, plus the OS-level accessibility toggle.
7. **LLM egress:** standalone mode sends screen content only to the configured endpoint, and non-local endpoints need per-endpoint opt-in. Screenshots are sent only to vision-capable models.
8. **No auto-send:** actions that transmit content to other people (send message, post) are blocked by default unless the task and plugin both opt in. (Drafting returns text to the caller.)

## Consequences

- Slightly more setup (copy the token into Hermes config).
- Distribution stays sideload-only; Google Play restricts autonomous AccessibilityService use, revisit before any store release.
- Defers mutual TLS; token + localhost/tailnet is adequate for personal use.
