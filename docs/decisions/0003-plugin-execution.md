# ADR 0003 — Plugins are declarative and execute on-device

Status: **Proposed** · Date: 2026-09-25 · **Refined by ADR 0005:** in v1 a plugin is a *playbook* (per-app hint text + optional state recognizers injected into the on-device agent's prompt); scripted navigation paths are deferred.

## Context

Earlier docs put plugins under a CLI-side path (`~/.android-harness/plugins/`) while describing on-device element resolution, leaving unclear who interprets them.

## Decision

1. A plugin is a **JSON data file** (no executable code in v1) describing: package, supported version range, navigation paths, state recognizers (keywords/roles), and named element hints.
2. The **device interprets** plugins. They are stored in the app's private files dir and installed through the API (`plugins push`) or `adb`.
3. The CLI is only a transport for install/list/test. It never interprets plugin logic.
4. Plugins may ship test scenarios executed via the CLI against a live device or emulator.
5. Plugins can only *narrow* behavior (hints, recognizers). They cannot bypass the app denylist or the no-auto-send rule (ADR 0002).

## Consequences

- One source of truth for plugin semantics (the device), so CLI, MCP and standalone mode behave identically.
- Data-only plugins are safe to share if the project is open-sourced.
- Limit: flows needing real logic must be expressed as agent guidance (LLM) or become a code change in the app until a scripting story exists.

## Example (schematic)

```json
{
  "name": "exampleapp",
  "package": "com.example.messagingapp",
  "versions": ">=24",
  "states": {
    "messages_list": {"anyText": ["Messaging"], "roles": ["list"]},
    "message_thread": {"anyText": ["Write a message"]},
    "empty_inbox": {"anyText": ["No messages yet"]}
  },
  "navigation": {
    "messaging": [{"tap": {"text": "Messaging", "role": "tab"}}]
  },
  "hints": {"unread_marker": {"textContains": "Unread"}}
}
```
