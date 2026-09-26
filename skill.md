---
name: android-ai-harness
description: Operate Android apps through the android-ai-harness (snapshot the screen, find elements, tap/type/scroll). Use when an agent must drive an Android app on a device or emulator via the harness MCP server.
---

> **STATUS: DRAFT / SPEC.** This file is for an **external agent driving the phone over MCP** (PC-driven mode). Today that is served by android-remote-control-mcp (ADR 0004); the tool names below describe the intended interface, so check the server's actual tool list. The on-device agent (you type a task on the phone) is a separate feature described in `docs/decisions/0005-on-device-agent.md`.

# android-ai-harness (agent guidance)

Tool schemas come from the harness MCP server (`/mcp`). This file covers *how to work well* with those tools.

## Connecting

| | |
|---|---|
| Endpoint | `http://127.0.0.1:8427/mcp` (emulator/USB via `adb forward tcp:8427 tcp:8427`) or the Tailscale address if LAN/tailnet binding is enabled in the app |
| Auth | `Authorization: Bearer <token>` (token shown in the app; mandatory) |

Hermes registration (verify header support):

```yaml
mcp_servers:
  android:
    url: http://127.0.0.1:8427/mcp
    # headers: {Authorization: "Bearer <token>"}
    timeout: 30
```

Debug without an agent:

```bash
adb forward tcp:8427 tcp:8427
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8427/api/snapshot
```

## Tools

`snapshot`, `find`, `tap`, `type`, `scroll`, `wait_for`, `screenshot`, `back`, `home`, `open_app`, `list_plugins`.

## Working loop

1. `snapshot` first. Read `package` and `title` to confirm where you are.
2. Act on elements by `ref` from the **latest** snapshot, passing its `snapshot_id`.
3. After each action, use the returned snapshot (or `wait_for`) before deciding the next step. Screens change asynchronously.
4. If an action returns `stale_ref`, use the fresh snapshot in the error and re-resolve; never reuse old refs.
5. If `find` returns multiple candidates, disambiguate using surrounding text/role; do not guess.

## Errors and special states

| Situation | What you get | What to do |
|---|---|---|
| Toast | `toast_shown` event / included in action result | Read it; treat as info unless it says something failed |
| Dialog | `dialog_appeared` with title and buttons | Handle it explicitly (dismiss/choose) before continuing |
| Loading | Progress indicator in snapshot | `wait_for` the target text with a timeout; don't spin on `snapshot` |
| Blind screen | `blind: true` with a reason (`FLAG_SECURE`, empty tree, password field) | Stop; ask the human. Do not attempt workarounds |
| Denylisted app | `app_denied` | Stop; the owner blocked this app on purpose |
| Blocked action | `action_blocked` (e.g. send/post without opt-in) | Return your draft to the human instead |

## Safety rules for agents

- Never send messages, post, purchase or delete unless the human explicitly asked for that exact action in this session and the plugin permits it. Prefer returning a draft.
- Do not read out passwords, codes or personal data beyond what the task needs.
- Stop and report on anything unexpected (unknown dialog, login wall, CAPTCHA).

## Plugins

Declarative JSON per app, interpreted on the device (ADR 0003). See `docs/decisions/0003-plugin-execution.md` for the format. In v1 a plugin is a *playbook* (per-app hint text plus optional state recognizers). The CLI is deferred, so install with `adb push` or the app UI.

## On-device mode (not this file's audience)

The phone can also run its own agent from a prompt typed on the phone (ADR 0005). It calls the same actions in-process, returns drafts instead of sending, and asks the user to confirm anything that sends, posts or deletes. External agents should not drive the phone while an on-device run is active.

## Related docs

- `docs/requirements.md` — requirements and scope
- `docs/decisions/` — ADRs 0001–0005
