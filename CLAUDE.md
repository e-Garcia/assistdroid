# android-ai-harness

Native Kotlin Android app that operates other apps through an `AccessibilityService`.

- **Primary (ADR 0005): on-device agent.** You type a task on the phone; the built-in agent loop (prompt → OpenAI-compatible LLM → tool calls → actions) drives the phone itself. Tools run in-process.
- **Secondary: PC-driven mode.** Hermes/Claude Code drive the phone over MCP. `android-remote-control-mcp` (ARC) covers this today (ADR 0004); our own HTTP/MCP server is optional.
- **Android only.** iOS has no cross-app accessibility API, overlays or background agent, so it is out of scope (see requirements §1.1).

Status: **ADR 0005 (on-device agent) accepted; phases 1 and 2 done.** The `android-harness` module has an `Automation` interface with an accessibility-backed implementation, an OpenAI-compatible LLM client (`llm/`), and the agent loop (`agent/`) that has completed real tasks on the emulator against the Mac Studio model. Tasks are started from `adb` for now. Not built yet: chat screen, foreground service, policy layer (denylist, confirm gate, audit log), playbooks, MCP. Personal use first; open-source possible later.

## Read first

- `docs/requirements.md` — scope, FRs/NFRs, testing strategy, open questions
- `docs/decisions/` — 0001 (MCP-first interface), 0002 (security), 0003 (plugins on-device, refined to playbooks), 0004 (prior art: ARC for PC-driven mode), **0005 (on-device agent, Kotlin, in-process tools)**
- `skill.md` — agent-facing usage guide (draft until code exists)

## Quick reference

| | |
|---|---|
| Language | Native Kotlin (no Flutter; see ADR 0005). CLI companion deferred |
| Package | `ga.egarcia.harness` |
| Primary automation | `AccessibilityService` (no root) |
| Primary interface | Prompt UI on the phone (chat screen, later overlay); agent calls tools in-process |
| Driven-mode interface | ARC today; ours optional: MCP at `/mcp`, JSON at `/api/*` |
| Default port | `8427`, bound to `127.0.0.1`; reach via `adb forward` |
| Auth | Mandatory bearer token |
| LLM | omlx on the Mac Studio (same provider and key as Hermes/Alice): `http://my-server:8000/v1`, model `Qwen3.6-35B-A3B-4bit`, tool calling + vision verified, thinking off; no on-phone inference in v1 |
| Test device | Samsung Galaxy Z Fold 4 (arm64); emulator `Harness_API36` until then |
| minSdk | 26 (screenshots need 30+ or MediaProjection) |

## Planned layout

```
android-ai-harness/
├── android-harness/     Android app (exists): AccessibilityService, snapshot builder, optional HTTP server;
│                        to add: Automation interface, agent loop, LLM client, prompt UI/service, policy, playbooks
├── sample-app/          Deterministic test app (known screens, dialogs, toasts, lists) - not yet created
├── fixtures/            Recorded snapshot JSON for device-free unit tests - not yet created
├── plugins/             Per-app playbooks (example_app.json, ...) - not yet created
└── docs/                requirements.md, decisions/
```

Only `android-harness/` and `docs/` exist. A shared `protocol` module and the CLI are deferred.

## Dev environment (this machine)

- Android SDK: `~/Android/Sdk` (`ANDROID_HOME` set); JDK 17; `adb` and `emulator` available; user is in `kvm` group.
- AVD: `Harness_API36` (Pixel 8, API 36.1, `google_apis`, **no Play Store**) is dedicated to this project. Start with `~/Android/Sdk/emulator/emulator -avd Harness_API36`. Do not use `Reldoc_API36_1` (belongs to reldoc).
- **ExampleApp gate:** do not run or automate ExampleApp until a throwaway account and a physical device are ready. Use the sample app and fixtures until then.
- Hermes is installed (`~/.hermes/config.yaml`); MCP servers are registered under `mcp_servers`.

## Conventions

- Follow the ADRs; if code must deviate, write/update an ADR first.
- Security defaults are not optional (ADR 0002): mandatory token, localhost bind, app denylist, no auto-send.
- Element refs are snapshot-scoped; stale refs must fail loudly (FR-11).
- Mark unverified assumptions with `// TODO(verify):` and list them in `docs/requirements.md` §7.
- **Git workflow (owner's rule):** `main` holds the initial scaffold and then only merges. Do all work on a feature branch (`feat/<topic>`), commit there, and merge into `main` (`git merge --no-ff`) only after `./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug` succeeds. If build or tests fail, do not merge. No Claude/Co-Authored-By trailers in commit messages. No remote yet; do not push unless asked.

## Spike results (2026-09-25, `Harness_API36` emulator, Settings app)

Verified: 401 without/with wrong token; snapshot merges list rows into one labelled clickable (`"Bluetooth · Connected devices > …"`); `tap`, `type`, `back`, `home` work; stale/unknown refs and non-editable targets return typed errors; bad JSON gives 400. Timing on a small screen: snapshot ~8 ms, tap ~57 ms, type ~25 ms (NFR-10..12 met, but re-measure on heavy screens like ExampleApp). 7 unit tests pass.

Run it:
```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug
adb install -r android-harness/build/outputs/apk/debug/android-harness-debug.apk
adb shell 'settings put secure enabled_accessibility_services ga.egarcia.harness/ga.egarcia.harness.HarnessAccessibilityService; settings put secure accessibility_enabled 1'
adb forward tcp:8427 tcp:8427
adb logcat -d -s Harness:I    # debug builds log the token
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8427/api/snapshot
```
If the service does not start, re-run the `settings put` right after install (the first attempt raced the install once).

## Prior-art evaluation (2026-09-25)

`android-remote-control-mcp` (ARC, MIT, on-device MCP) was installed on the emulator and works with the same flows (ADR 0004). Decision (Proposed): ARC serves PC-driven mode; the on-device prompt-on-phone agent is built here (ADR 0005). Do not add more HTTP/MCP server features to the spike; build the `Automation` interface and agent loop instead.

Existing on-device agent apps (PrivateAgent original `orailnoor/private-agent`, OpenDroid, MobileAppAgent) are **reference reading only**: PrivateAgent has no license (do not copy code), is Flutter, and its release APKs are debug-signed with no provenance. Never install a prebuilt APK on the real phone without building it from a pinned source commit; ARC's release APK was installed on the emulator only, unverified.

On the emulator both ARC (port 8080, throwaway eval token) and our spike (8427) are installed and enabled; `adb forward` is set up for both. Remove ARC with `adb uninstall com.danielealbano.androidremotecontrolmcp` when done.

## Known gaps / TODO

- No chat screen, foreground service, policy layer (denylist, confirm gate, audit log) or playbooks yet. The agent audit log (`files/agent-audit.log`) is a plain text stub and can contain typed text.
- Model quality: the 35B-A3B sometimes calls `look` when the element list suffices and once issued a stray `back` after a screenshot; the prompt now says to trust the element list. Wrong answers from screenshots are possible, so `done` summaries should be treated as unverified until the policy layer exists.
- The emulator cannot resolve MagicDNS names (`my-server`); use the Tailscale IP there.
- Snapshot reads only the active window (multi-window and foldable behaviour are follow-ups, see ADR 0005).
- `wait_for` cannot interrupt a snapshot already in progress, so it can overshoot its timeout by about one snapshot.
- `FLAG_SECURE` blindness is not detected (only null window / empty tree).
- `AccessibilityNodeInfo.isChecked` is deprecated on API 36 (compile warning); switch to `checked` state API when raising minSdk or guard by SDK.
- Nodes are not recycled (fine on API 33+, minor leak on 26-32).
- No audit log, denylist or kill switch yet (ADR 0002); token is only debug-logged.

## Phase 1 results (2026-09-25, emulator)

New driven-mode routes (curl, bearer token): `GET /api/screenshot?max_side=`, `POST /api/scroll|swipe|wait_for|open_app`. Verified on `Harness_API36`: `open_app` by label or package (unknown label and ambiguous label return typed errors; an exact label match wins over partial matches), scroll via accessibility action in Settings (gesture fallback exists), `wait_for` found/timeout, swipe gesture, stale/missing ref errors, JPEG screenshot (450x1000, about 37 KB, correct content) with automatic retry when Android rate-limits screenshots. Snapshot JSON now uses the key `package`. 13 unit tests pass.

## Phase 2 results (2026-09-25, emulator + Mac Studio model)

Run it (the API key comes from Hermes' `~/.hermes/.env`, `OMLX_API_KEY`, and is never printed):
```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug   # 42 unit tests
adb install -r android-harness/build/outputs/apk/debug/android-harness-debug.apk
adb shell 'settings put secure enabled_accessibility_services ga.egarcia.harness/ga.egarcia.harness.HarnessAccessibilityService; settings put secure accessibility_enabled 1'
scripts/configure_llm.py --base-url http://100.94.145.51:8000/v1 --model Qwen3.6-35B-A3B-4bit   # emulator: tailnet IP
scripts/run_task.py "Open the Settings app, find the About page, and tell me the Android version"   # Ctrl-C sends STOP
```
The config and run receivers (`AdbConfigReceiver`, `AdbAgentReceiver`) require the DUMP permission, so only `adb` can call them. The API key is stored encrypted with an Android Keystore key.

Verified: 5-step Settings task returned "Android 16" (correct); typing task (search for Bluetooth) completed; STOP ended a run within about 1 s with no further actions; each step costs about 1.7 to 2.3K input and 13 to 60 output tokens; `CleartextPolicy` blocks plain HTTP to public hosts; the key never appears in logcat (`LlmConfig.toString` redacts it). Lesson: `adb shell` re-parses arguments, so keys with shell metacharacters must be quoted (the scripts do this).

## Next steps (ADR 0005 phases)

1. **Phase 3:** chat screen (task box, live step log, result), foreground service with an ongoing notification and STOP, run lock. Add an LLM settings screen (replaces the adb receiver for normal use).
2. **Phase 4:** policy layer: app denylist, confirm gate for send/post/delete/purchase/call, structured audit log without screen contents. Then the overlay bubble and playbooks.
3. Agent quality: compare `Qwen3.8-27B-4bit`, tune the prompt, consider a lower-token screen format and a screenshot-use policy.
4. Foldable AVD (Pixel Fold definition) for layout and posture testing; sample app + recorded fixtures.
5. On the Fold 4: allow restricted settings, disable Auto Blocker if needed, exclude from battery optimization, enable Tailscale, run the same tasks. ExampleApp stays gated (throwaway account + physical device).
6. Later: Hermes-on-Termux + ARC comparison.

## Not in v1

ADB fallback, iOS (not portable), CLI companion, scripted plugin navigation, multi-device, scheduling, history beyond the local audit log, Play Store distribution, on-phone LLM inference.
