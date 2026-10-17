# ADR 0004 — Prior art: adopt android-remote-control-mcp or keep building?

Status: **Accepted** · Date: 2026-09-25 · **Scope narrowed by ADR 0005:** adopting ARC applies to the PC-driven (Hermes/MCP) mode only. The on-device prompt-on-phone agent is built in this repo (ADR 0005).

## Context

Before investing past the spike we evaluated existing projects. The closest is
[android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp) ("ARC"): Kotlin, MIT, on-device MCP server over Streamable HTTP, accessibility-based. We cloned it, installed the v1.12.0 `foss-release` APK on `Harness_API36` (API 36.1 emulator), configured it headlessly over `adb`, and ran the same Settings-app flow as our spike.

## Findings

Other projects (from README/search only, not run): [Mobilerun Portal](https://github.com/droidrun/droidrun-portal) + [Mobilerun](https://github.com/droidrun/mobilerun) (on-device Kotlin service with local HTTP/WS API; Python agent host-side; no MCP mentioned), [mobile-mcp](https://github.com/mobile-next/mobile-mcp) (host-side `adb`, Apache-2.0), Appium/UiAutomator2 (test framework, heavy for LLM loops).

ARC, verified by running it:

| Area | ARC v1.12.0 | Our spike |
|---|---|---|
| Transport / auth | MCP Streamable HTTP `/mcp`, JSON-only, bearer token (+ optional OAuth), localhost default | Plain HTTP `/api/*`, bearer token, localhost. **No MCP yet** |
| Reached via | `adb forward` works; Hermes `mcp_servers` supports `headers:` (checked in Hermes' `cli-config.yaml.example`) | same |
| Config security | Config and start/stop are `adb`-only (`DUMP`-gated), per-tool and per-parameter disable switches | none yet |
| Tool surface | 44 tools listed (57 documented): node find/click/tap, gestures, typing, clipboard, wait, app/intents, files, notifications, camera, location | 5 |
| Screen state, Settings home | ~9.9 KB, all windows (status bar, IME), node table plus hierarchy tree, title/summary as separate rows | ~1.5 KB, 12 elements, list rows merged into one label |
| Latency (state) | ~30 ms | ~8 ms |
| Tap on a label whose parent is clickable | `click_node` fails ("not clickable"); `tap_node` (gesture) works | walks up to the clickable ancestor |
| Typing | Humanized (~250 ms/char, variance); 933 ms for 4 chars | instant `ACTION_SET_TEXT` |
| Prompt-injection guard | Wraps screen text in an "untrusted data" warning | none |
| Tests | Compose test app + e2e-tests + unit tests | 7 unit tests |
| minSdk | **33** | 26 |
| Size / scope | 176 MB APK (incl. ~150 MB on-device privacy model); many optional permissions | 5.8 MB |
| Maintenance | ~974 commits, release Aug 2026, MIT | n/a |
| Not provided | app allowlist/denylist, audit log, draft-only policy, plugins, agent loop | same, plus not built |

Also verified: on both apps, the first `settings put secure enabled_accessibility_services` right after `adb install` was silently ignored once; re-running it works.

## Options

1. **Adopt ARC as the on-device tool server (recommended).** Connect Hermes to it now. This repo becomes the layer around it: provisioning scripts, Hermes config, app knowledge as Hermes skills, safety policy, test scenarios. Optionally a host-side **MCP policy proxy** (Hermes → proxy → ARC) that enforces app denylist, draft-only/no-send rules and a host-side audit log, since ARC has only per-tool switches.
2. **Fork ARC** and add missing features (compact merged-row snapshot mode, app denylist, audit log). More control, but we take on a large codebase with strict contribution rules (upstream forbids AI attribution).
3. **Keep our own server** (finish MCP, `find`, `scroll`, `wait_for`, etc.). Full ownership, smallest footprint, best token efficiency and minSdk 26, but re-implements what ARC already ships and maintains.

## Recommendation

Option 1 now; keep the spike as a documented baseline. Revisit options 2/3 only if a concrete blocker appears:

- **Token cost:** ARC's state is ~6.5x larger on this screen. If that hurts a small local model in standalone mode, contribute a compact mode upstream or revive our snapshot builder.
- **Device API level:** ARC needs Android 13+. If the chosen physical device is older, option 3 is the fallback.
- **Policy enforcement:** if the proxy is too clumsy, contribute a per-app denylist upstream.

Consequences of option 1 *for PC-driven mode*: Hermes is the agent and per-app knowledge lives in Hermes skills. (ADR 0005 supersedes the earlier idea that this removes the on-device agent loop: the primary goal is a prompt on the phone, so the agent loop and per-app playbooks stay in scope.) ADRs 0001-0003 remain valid as requirements for whichever server is used; ADR 0002 becomes a checklist to configure/enforce rather than to implement.

## Open items

- Confirm Hermes can run against a local/OpenAI-compatible model well enough to serve as the standalone agent.
- Check ARC behaviour on stale node ids after screen changes (node ids are hashes; not tested).
- Check ARC's Restricted Settings behaviour on the chosen physical device (documented issue on some Android 13+ OEM builds).
- Decide whether the policy proxy is worth building.
