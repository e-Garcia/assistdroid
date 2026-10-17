# Requirements — AssistDroid

Status: **spike built; on-device agent designed (ADR 0005, Proposed)** · Last updated: 2026-09-25 · Author: Erick Garcia

## 1. Problem Statement

**Primary goal:** you type a task on the phone and the phone operates itself to finish it, the way Hermes does on a PC with computer use. **Secondary goal:** an agent on another machine (Hermes, Claude Code) can drive the phone.

AI agents have no reliable way to operate Android apps the way a person does. The harness runs on the device and provides:

1. A compact, LLM-friendly view of what is on screen.
2. Human-like actions (tap, type, scroll, back) addressed by element reference, not pixel coordinates.
3. A built-in, prompt-driven agent loop with a prompt UI on the phone (primary).
4. A stable network interface (MCP over HTTP) so an agent on another machine can drive the phone (secondary; ARC serves this today, see ADR 0004).
5. Per-app knowledge (playbooks) so common flows are reliable.

### 1.1 Scope and audience

- **Primary audience (ADR 0007): people who need their phone operated or explained, not power users.** Elderly
  users, first-time Android/smartphone users, and people with disabilities. Three shapes of help, chosen per
  task: *"increase my brightness"* (do it), *"how do I send an email?"* (guide me, step by step, so they learn
  the path), *"why is this message about a crash or low storage on my screen? How do I fix it?"* (explain this,
  read-only). Not yet implemented; see ADR 0007 for the interaction-mode design and sequencing.
- **v1 built so far: personal use.** Sideloaded APK, one owner, own devices/emulators. Play Store distribution
  was out of scope under that framing; ADR 0007 reopens it, conditional on the `isAccessibilityTool` decision.
- **Open source:** in progress — license, package, project name and git history all resolved (see
  `docs/open-source-readiness.md`); repository creation still pending.
- **Platform: Android only.** iOS has no cross-app accessibility API, no overlays and no long-running background agent, so the on-device agent cannot exist there. A future iOS story would be a separate host-driven tool server (WebDriverAgent from a Mac) behind the same MCP interface, not a port of this app. This is also why the app is native Kotlin, not Flutter (ADR 0005).

### 1.2 Prior art (evaluated 2026-09-25; see ADR 0004)

Existing tools cover most of the driven-mode core. The closest, [android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp) (Kotlin, MIT, on-device MCP over Streamable HTTP), was installed and exercised on the harness emulator: it works with Hermes-style bearer-token MCP, is actively maintained and ships tests. Others: Mobilerun Portal/Mobilerun, mobile-mcp (host-side `adb`), Appium/UiAutomator2.

**Decision (ADR 0004 refined by ADR 0005; both Proposed, awaiting owner confirmation):**
- ARC serves the **PC-driven** Hermes mode. Nothing to build for that in v1.
- The **on-device, prompt-on-phone agent** (§3.6) is built in this repo, in Kotlin, in-process with the accessibility service.
- Existing on-device agent apps (PrivateAgent, OpenDroid, MobileAppAgent) are reference reading only: PrivateAgent has no license, is Flutter, requests broad permissions (SMS, calls, contacts) and its release APKs are debug-signed with no provenance. Nothing is copied from them.

## 2. Operating Modes

| Mode | Who decides what to do | Interface |
|---|---|---|
| **On-device (primary)** | Built-in agent loop calling an OpenAI-compatible LLM | Prompt on the phone: chat screen, later a floating overlay |
| **Driven (secondary)** | External agent (Hermes, Claude Code) | MCP over HTTP (ARC today; our own server optional), plain HTTP/WebSocket |

Both modes use the same automation core. On-device, the agent calls it in-process (no HTTP hop, no token); driven mode reaches it over MCP/HTTP. Only one controller should drive the phone at a time.

## 3. Functional Requirements

### 3.1 Automation core

- **FR-01**: Capture the current screen as a *snapshot* (§3.3) from the accessibility node tree.
- **FR-02**: Resolve a label/role/text query to zero or more candidate elements (with ambiguity reported, never silently picking one).
- **FR-03**: Tap an element by reference (`ACTION_CLICK`; `dispatchGesture` as fallback).
- **FR-04**: Set/append text in an editable element (`ACTION_SET_TEXT`; IME/paste fallback).
- **FR-05**: Scroll a scrollable element or the screen in a direction.
- **FR-06**: `wait_for` text/element/screen-change with timeout; surface toasts and dialogs as events.
- **FR-07**: `back`, `home`, `open_app(package|name)`.
- **FR-08**: Screenshot (`AccessibilityService.takeScreenshot` on API 30+; `MediaProjection` fallback below, requiring a one-time user grant).
- **FR-09**: Report *blindness* explicitly (`FLAG_SECURE` window, empty tree, password field content withheld) instead of returning an empty snapshot.

### 3.2 Access modes

- **Mode A: AccessibilityService** (primary, no root).
- **Mode B: ADB fallback** (stretch goal, not v1). Requires implementing the ADB protocol in-app or using a library (Shizuku / libadb-android style); wireless debugging on Android 11+ uses pairing and a random port, so a fixed `127.0.0.1:5555` is not reliable.
- **Mode C: Combined** (A, falling back to B) once B exists.

### 3.3 Snapshot and element references

- **FR-10**: Snapshot is compact JSON/text: foreground package, activity/window title, and a pruned tree of elements with `ref`, `role`, `text`/`desc`, `bounds`, and flags (`clickable`, `editable`, `scrollable`, `checked`, `focused`). Non-informative containers are collapsed to save tokens.
- **FR-11**: `ref` values are valid only for the snapshot that produced them. Each snapshot has an id; actions must cite it. A stale ref returns an explicit `stale_ref` error with a fresh snapshot instead of acting on the wrong node.
- **FR-12**: Every action result includes success/failure, an error code, and (optionally) the post-action snapshot.

Example:
```json
{
  "snapshot_id": "s_41",
  "package": "com.example.messagingapp",
  "title": "Messaging",
  "elements": [
    {"ref": "e7", "role": "button", "text": "Compose", "clickable": true, "bounds": [900,120,1040,240]},
    {"ref": "e12", "role": "listitem", "text": "Ada Lovelace · Unread · Re: role", "clickable": true}
  ]
}
```

### 3.4 External interfaces

- **FR-20**: MCP server (streamable HTTP) at `/mcp` exposing one tool per action: `snapshot`, `find`, `tap`, `type`, `scroll`, `wait_for`, `screenshot`, `back`, `home`, `open_app`, `list_plugins`.
- **FR-21**: Plain HTTP JSON API mirroring the tools (debugging with `curl`) and a WebSocket event stream: `screen_changed`, `toast_shown`, `dialog_appeared`, `action_executed`, `error_occurred`.
- **FR-22**: Bearer-token auth on every endpoint (see §4.1 and ADR 0002).

### 3.5 Plugins

- **FR-30** *(playbooks implemented, see docs/playbooks.md)*: Plugins are declarative JSON files (data, not code) per app: package, supported version range, navigation paths, state recognizers, named element hints. **v1 scope (ADR 0005): a plugin is a *playbook*, i.e. per-app hint text plus optional state recognizers, injected into the agent's prompt while that package is foreground.** Scripted navigation paths are deferred.
- **FR-31**: Plugins execute **on the device**. They are installed to the app's private storage via the API or `android-harness-cli plugins push`. (ADR 0003.)
- **FR-32**: A plugin can ship test scenarios runnable via the CLI against a live device.

### 3.6 On-device agent (prompt on the phone)

- **FR-40**: LLM client speaks the OpenAI-compatible chat-completions API only. Base URL, model, API key and prompt template are configuration, so local servers (Ollama, llama.cpp, LM Studio, on a PC over Tailscale/LAN) and cloud providers both work. Requires tool/function-calling; a JSON-action fallback for models without it is an open question (§7).
- **FR-41**: Agent loop: observe (snapshot, optional screenshot) → LLM → validated tool call → act → repeat. Only the latest snapshot is sent in full; older ones are dropped or summarized to bound context. Limits: max steps (default 30), per-run timeout, stuck detection (same screen and same action repeated) that stops and asks the user. Screen text is wrapped as untrusted data and the system prompt tells the model to ignore instructions found inside it.
- **FR-42**: Images are sent only when the configured model is marked vision-capable; otherwise text-only snapshots.
- **FR-43**: Tasks that produce text for a human (e.g. *draft a reply*) return the draft to the user. **The agent never sends/submits on its own** (see FR-48).
- **FR-44**: Any screen content leaving the device to a non-local LLM endpoint requires explicit per-endpoint opt-in.
- **FR-45** *(implemented: chat screen and floating overlay)*: Prompt UI. v1: chat screen (task input, live step log, result, history). v1.1: floating overlay (prompt, status, STOP) using an accessibility overlay window. Voice input (optional, off by default): Google speech or Local Whisper, see `docs/voice-input.md`.
- **FR-46**: Agent tools, in-process: observe (`snapshot`, `screenshot`), act (`tap`, `type`, `scroll`, `swipe`, `back`, `home`, `open_app`, `wait`), control (`done`, `ask_user`, `confirm`).
- **FR-47**: STOP is always reachable (ongoing-notification action and UI). It cancels the run immediately, releases wake locks and leaves the phone as it is.
- **FR-48** *(implemented, ADR 0002)*: Confirm gate. Actions that transmit or destroy (send, post, submit, delete, purchase, call, SMS) are blocked by the tool layer until the user approves on screen. Enforced in code (button-text heuristics, playbook markers, model `confirm`), not left to the model.
- **FR-49** *(lock screen and setup guidance implemented, see docs/setup.md)*: Run constraints. Runs as a foreground service with an ongoing notification. The device must be unlocked and awake; on keyguard, biometric or permission prompts the agent pauses and asks the user. It cannot bypass a secure lock screen. The user should not use the phone during a run. While a task runs the phone is kept awake by three layers (keep-screen-on overlay window, dim screen wake lock, partial CPU wake lock; all self-releasing) and can be dimmed to a configurable brightness, which returns to normal while an approval is pending.
- **FR-50** *(implemented, `docs/inbox-digest.md`)*: Notification and email digest. Read-only tools `read_notifications` and `read_email_digest` let the agent check messages without operating a messaging app. Opt-in: notifications only from apps the owner switches on (never from denylisted apps), stored encrypted for at most 7 days; email over IMAPS only (read-only folder, `BODY.PEEK`, verified certificate, sender allowlist by real address, app password in the Keystore). Results are framed as untrusted content; the audit log records counts, never text. Not verified against a real mail provider yet.

### 3.7 CLI companion (`android-harness-cli`, Kotlin) — deferred

Not needed for v1: the phone is the UI, and `adb`/`curl` cover debugging. Kept here in case a PC-side trigger becomes useful.


- **FR-50**: Kotlin/JVM CLI sharing a `protocol` module with the Android app.
- **FR-51**: Commands mirror the tools (`snapshot`, `find`, `tap`, `type`, ...), plus `agent run "<prompt>"` for standalone mode and `plugins push|list`.
- **FR-52**: Connect over `adb forward` (localhost), LAN, or Tailscale.

### 3.8 Agent integration

- **FR-60**: Primary integration is the MCP endpoint; Hermes registers it under `mcp_servers` (verify header/auth support when building).
- **FR-61**: `skill.md` remains as human/agent guidance (workflow tips, error recovery) but tool schemas come from MCP, not from prose.

## 4. Non-Functional Requirements

### 4.1 Privacy & security
- **NFR-01**: Bearer token is mandatory, generated on first run, never default/empty.
- **NFR-02**: Default bind is `127.0.0.1` (reach via `adb forward`); LAN/Tailscale bind is an explicit opt-in.
- **NFR-03**: No outbound calls except to the user-configured LLM endpoint, and only during an on-device agent run. No telemetry, no screenshot uploads elsewhere. Cleartext HTTP is allowed only to local/tailnet hosts, enforced in the LLM client (ADR 0005).
- **NFR-04** *(implemented)*: App denylist (banking, password managers, authenticators) refused by default; password field values never returned.
- **NFR-05** *(implemented)*: Audit log of every action with timestamp and caller; visible in the app.
- **NFR-06**: One-tap kill switch in the app; disabling the AccessibilityService stops everything.

### 4.2 Performance (targets, verified in the spike)
- **NFR-10**: Snapshot < 500 ms on a modern device.
- **NFR-11**: Element resolution < 200 ms.
- **NFR-12**: Tap completes < 300 ms after command.

### 4.3 Compatibility
- **NFR-20**: minSdk 26; screenshot needs API 30+ or MediaProjection.
- **NFR-21**: Works with native, Flutter and React Native UIs where they expose accessibility nodes; report blindness otherwise.
- **NFR-22**: Handle dynamic content (lists that virtualize, bottom navigation).

### 4.4 Reliability
- **NFR-30**: Runs as a foreground service; documents the battery-optimization exemption needed on real devices.
- **NFR-31**: Detect and report system dialogs (permission, keyguard) rather than acting blindly.

## 5. Reference Scenario (real app, kept private)

A canonical scenario for plugin development and integration testing is defined against a specific real-world
messaging app — read a thread, identify unread conversations, draft (never send) a reply. It is deliberately
kept out of this public repository: the target app's terms of service prohibit automation, and documenting the
exact navigation steps for a named company's app isn't something this project wants in its public history (see
`docs/open-source-readiness.md`). The scenario, its target app and its caveats live in the private, gitignored
`private/real-app-reference-scenario.md` (see `CLAUDE.local.md`).

**Gate: the real-app scenario is not run until (a) a throwaway account for that app exists and (b) a physical
device is available.** Until then, all development and testing uses the sample app and recorded fixtures (§6).

## 6. Testing Strategy

1. **Unit tests** against recorded snapshot fixtures (JSON captured from real screens); no device needed.
2. **Deterministic device tests** against a small in-repo sample app with known screens, dialogs, toasts, lists.
3. **Agent-loop tests** against a fake OpenAI-compatible server that returns scripted tool calls (JVM tests, then on the emulator against the sample app).
4. **Real-app scenario** (private, see §5) as a manual/on-demand smoke test only (not CI).

## 7. Open Questions

- **Guide/explain modes (ADR 0007), not yet speced as FRs.** Needs: a mode-selection surface (ask, or infer from
  the task — "how do I..." implies guide, a direct imperative implies do, a question about something on screen
  implies explain), an `explain_screen` read-only tool (closely related to `read_notifications` but for whatever
  is currently foreground, not just notifications), and a "narrate instead of act" path through the existing
  overlay/playbook machinery for guide mode. Whoever picks this up next: start here, then write the FRs, then
  implement explain mode first (cheapest, closest to existing code).
- ~~Which MCP Kotlin server library and HTTP engine fit on Android?~~ Answered: the official MCP Kotlin SDK on Ktor works on Android (used by ARC, ADR 0004).
- ~~Hermes MCP client: bearer header support?~~ Answered: Hermes HTTP MCP servers accept `headers:`. Still open: behavior when the device is offline, and Hermes with a local model.
- Snapshot pruning heuristics: our merged-row snapshot was ~6.5x smaller than ARC's on the same screen; does it matter for small local models?
- Build vs adopt: ARC for PC-driven mode, own on-device agent (ADR 0004 + 0005, Proposed, awaiting owner confirmation).
- ~~Which LLM first?~~ Answered and verified (ADR 0005): the omlx provider on the model server, `Qwen3.6-35B-A3B-4bit`; tool calling and image input both work; thinking off by default.
- Fallback action format for local models with weak or no function-calling. (The 35B-A3B calls tools reliably; it occasionally writes `done: ...` as prose, which the loop now accepts.)
- ~~How to keep the screen awake during a run?~~ Answered: accessibility overlay window with `FLAG_KEEP_SCREEN_ON` (ADR 0005); to be confirmed on the Fold 4.
- Confirm-gate heuristics: how to recognize Send/Post/Delete/Buy reliably across apps and languages.
- ~~Physical device?~~ Answered: Samsung Galaxy Z Fold 4 (arm64). Still to confirm: installed Android version, Samsung sideloading/battery restrictions, and foldable behaviour (two displays, multi-window; ADR 0005).
- ARC stale-node-id behavior and Restricted Settings on the chosen physical device (needs testing).
- Fine-tune a small model specifically on this project's own tool schema and snapshot format, rather than relying
  on prompting a general model. Plan and tooling: `docs/model-finetuning.md`, `finetune/` (data collection and
  dataset-prep tooling built and smoke-tested against the real model server; training itself needs the Mac
  Studio, not yet run). Open: whether the accuracy/latency/token-cost win is worth the maintenance cost of
  retraining whenever the tool schema changes; the safety layer (`PolicyAutomation`) stays enforced in code
  regardless of which model drives the loop, fine-tuned or not — this is about efficiency and reliability, not
  safety, which must not depend on model training.

## 8. Out of Scope for v1

- ADB fallback (Mode B/C), iOS (not portable, see §1.1), the CLI companion, scripted plugin navigation, multi-device switching, scheduling, conversation history beyond the local audit log, Play Store distribution, on-phone LLM inference.
