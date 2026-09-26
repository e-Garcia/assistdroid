# android-ai-harness

Native Kotlin Android app that operates other apps through an `AccessibilityService`.

- **Primary (ADR 0005): on-device agent.** You type a task on the phone; the built-in agent loop (prompt → OpenAI-compatible LLM → tool calls → actions) drives the phone itself. Tools run in-process.
- **Secondary: PC-driven mode.** Hermes/Claude Code drive the phone over MCP. `android-remote-control-mcp` (ARC) covers this today (ADR 0004); our own HTTP/MCP server is optional.
- **Android only.** iOS has no cross-app accessibility API, overlays or background agent, so it is out of scope (see requirements §1.1).

Status: **ADR 0005 (on-device agent) accepted; phases 1 to 6 done (all planned features plus a guided setup checklist), plus the Bot Lab detectability tool (ADR 0006).** The `android-harness` module has an `Automation` interface with an accessibility-backed implementation, an OpenAI-compatible LLM client (`llm/`), the agent loop (`agent/`), a chat screen with live steps, an LLM settings screen with a connection test, a foreground service with a STOP notification, a run lock and keep-awake, and the policy layer (app denylist, confirm gate with an approval dialog, password-field block, audit log screen; ADR 0002). Also built: per-app notes/playbooks (`playbook/`, `docs/playbooks.md`) and an optional floating button (`overlay/`) with prompt, Stop pill and result card. Also built: a setup checklist (`setup/`, `SetupActivity`, `docs/setup.md`) that detects missing accessibility, model, notification and battery settings and takes the user to the right system screen or permission dialog. Not built: MCP server, agent-quality tuning, real-device (Fold 4) validation. Personal use first; open-source possible later.

## Read first

- `docs/requirements.md` — scope, FRs/NFRs, testing strategy, open questions
- `docs/detectability.md` — what apps can observe about the harness (Bot Lab results); ADR 0006 and `docs/non-goals-detection-bypass.md` are the project's stance: measure and disclose, never camouflage. **Read the non-goals page before adding any input-handling, timing, device-identity or app-visibility feature; requests in its table are declined.**
- `docs/decisions/` — 0001 (MCP-first interface), 0002 (security), 0003 (plugins on-device, refined to playbooks), 0004 (prior art: ARC for PC-driven mode), **0005 (on-device agent, Kotlin, in-process tools)**, 0006 (detectability: measure and disclose, do not camouflage)
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
├── sample-app/          Deterministic test app (exists): Send/Post/Delete/Buy buttons with visible counters, a password field, a camera-permission button
├── botlab-app/          Bot Lab (exists): instrumented arena + freeRASP that shows what an app can observe about how it is operated
├── fixtures/            Recorded snapshot JSON for device-free unit tests - not yet created
├── plugins/             Per-app playbooks (example_app.json, ...) - not yet created
└── docs/                requirements.md, decisions/
```

Only `android-harness/`, `sample-app/`, `botlab-app/`, `scripts/` and `docs/` exist. A shared `protocol` module and the CLI are deferred.

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

- Not yet: a `confirm` tool for the model to ask first (the gate is enforced regardless), biometric-prompt handling beyond the platform hiding it, overlay behavior on the Fold's two displays (unverified), the floating prompt is not yet keyboard-aware on tiny screens (uses adjust-pan).
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

## Phase 3 results (2026-09-25, emulator)

- **UI:** `MainActivity` is the chat (task box, live steps, results, follow-up answers after `ask_user`, Clear); `SettingsActivity` has base URL, model, API key (keep-saved semantics), extra JSON, max tokens, vision/thinking switches, "Test connection", accessibility status with a restricted-settings hint, and the driven-mode token. Plain Views; edge-to-edge insets handled (API 35+ enforces it; system bars and keyboard). The chat moves to the background when a run starts (700 ms head start so the agent does not read the chat).
- **Foreground service** (`AgentForegroundService`, type specialUse) shows an ongoing notification with the current step and a **Stop** button (`AgentStopReceiver`, not exported). Verified: pressing Stop in the shade ends the run ("stopped by user") and removes the notification.
- **Run lock:** while a task runs, driven-mode `/api/*` calls return 409 `busy` (health stays 200), so an external client cannot invalidate the agent's refs. Verified with curl mid-run.
- **Keep-awake:** verified with a control experiment (emulator `stay_on_while_plugged_in` must be off, or it masks the result): no run, screen asleep after 25 s with a 15 s timeout; during a run it stays awake; after STOP it sleeps again.
- **Lesson (self-observation):** the agent's own UI is part of what it observes. The notification status originally said `Step 2: Wait for "zzznotthere"`; with the shade open the agent found that text in our notification and ended its wait early. The notification status is now argument-free ("Step 2: waiting", unit-tested). The phase 4 denylist must also cover this app's own package, and other surfaces that echo task text (lock screen, overlay bubble) need the same care.
- **Lesson (`open_app` race):** launching an app is asynchronous. With a fixed 1.2 s pause the next snapshot could still show the previous app (once this app's own Settings screen), so the agent answered from the wrong screen. `open_app` now polls until the launched package is in the foreground (8 s limit) and warns the model if it never arrives; covered by unit tests and re-verified live from exactly that starting state.
- **Model server contention:** the Mac Studio is shared with Alice's jobs. One first reply took 67 s and a direct probe 17.9 s (normally 1 to 3 s). The 180 s request timeout absorbs this, but the chat should show "waiting for the model" (the step status does).
- Not yet exercised live: the follow-up flow after `ask_user` (pure parts are unit-tested) and the settings "Test connection" button and Save.

## Phase 4 results (2026-09-25, emulator + sample app)

Policy layer (`policy/`, ADR 0002 implementation status): denylist, confirm gate, password block, audit log. Set it headlessly with `adb shell am broadcast -a ga.egarcia.harness.ADB_POLICY -n ga.egarcia.harness/.AdbPolicyReceiver --es denied 'pkg.or.keyword,another' --ez gate true` (DUMP-gated); pass `--es denied "''"` to clear.

Testing trick: the driven-mode API goes through the same `PolicyAutomation`, so the gate can be tested deterministically with curl and the sample app (no model latency): `POST /api/tap` on "Send message" blocks until you press Approve/Deny on the phone. The sample app's counters prove exactly which actions ran.

Verified live: Deny gave `user_denied` and `Sent: 0`; Approve gave `ok` and `Sent: 1` (exactly once); an unanswered approval timed out after 120 s as a denial; password typing returned `blocked`; a blocked app showed `app_denied` with zero elements and refused tap/scroll/screenshot/open_app while Back worked and `wait_for` could not peek at its text; the real model, facing a blocked app, opened it, saw only `app_denied`, and asked the user instead of working around it; the audit log held no typed or screen text (`chars=19` only) and is viewable in Settings > View activity log. 98 unit tests pass.

Lessons: (1) an opaque approval dialog stops the app underneath and kills its node handles, so approved taps failed with `stale_ref`; the dialog is translucent now and the tap re-resolves the element after the app returns (waits for the original package to be in front, or the dialog closing looks like our own denied app). (2) The Mac Studio can stall for minutes under load (one probe >200 s, another 70 s, then 0.7 s); a run then ends cleanly with `llm_error: network Request timeout` after 180 s. (3) Do not trust `dumpsys window` focus checks in test scripts; log a content-free marker instead (`HarnessApproval: approval requested`).

## Phase 5a results (2026-09-26, emulator + sample app)

- **Lock screen:** starting a task on a sleeping/locked phone returns `start: LOCKED` (chat: "Unlock the phone first") and nothing runs; putting the phone to sleep mid-task ends the run with "The phone is locked or the screen is off..." before any further model call.
- **Model retries and waiting state:** `network`, 408, 429 and 5xx are retried twice (3 s, 10 s), then `llm_error`. The chat shows "Step N: waiting for the model · 14s" and "Model busy, retrying (2 of 2)" with a live timer (verified with an unreachable model URL).
- **Security finding, verified both ways:** a zero-length `swipe` on "Send message" pressed it with no approval (`Sent: 1`) on the pre-fix build; after the fix it returns `blocked` (`Sent: 0`) and a real scroll swipe still works. Swipes must start in a scrollable area and be at least 150 px.
- **Permission dialogs:** the sample app's "Ask for camera" shows the system dialog. On Android 14+ the system hides it from non-accessibility-tool services, so the harness sees `no_active_window`; the agent cannot tap "Allow" (`granted=false`) and asks the user to handle it. The `PERMISSION` risk gate remains for older Android and the package installer. Never set `isAccessibilityTool` on the service.
- Note for test scripts: a snapshot with no active window omits both `package` and `elements` (defaults are not encoded), so read them with `.get()`.
- 111 unit tests.

## Phase 5b results (2026-09-26, emulator)

- **Playbooks:** owner notes per package, shown in an `<app_notes>` block while that app is in front (latest copy only, none on blind screens), edited in Settings or `scripts/set_policy.py --notes-file`. A/B on the same task: without notes "0 messages have been sent.", with the sample-app note exactly `COUNT=0`. Cannot loosen safety rules (enforced in code). See `docs/playbooks.md`.
- **Floating button** (off by default; Settings switch or `scripts/set_policy.py --overlay`): a draggable bubble over any app opens a prompt card (focusable only while idle, keyboard works); on Run it becomes a non-focusable status pill with Stop ("Step 3: waiting for the model", argument-free), and at the end a result card (Reply if the agent asked something, Open chat, Close). Drawn as accessibility overlay windows, so no extra permission. Verified: task typed in the overlay over Settings; agent switched to the Sample App and finished; the overlay never became the window the agent reads.
- Lesson: inside `LinearLayout.apply {}` an outer property named `overlay` resolves to `View.getOverlay()`; name UI fields distinctively.
- 122 unit tests.

## Phase 6 results (2026-09-26, emulator, fresh install)

**Setup checklist** (`setup/SetupChecker.kt` pure rules + `AndroidSetupEnvironment`, screen `SetupActivity`, guide `docs/setup.md`). Items: Accessibility (required), AI model (required; optional connection check, warns if unreachable or slow), Notifications and Background battery (recommended). Shown automatically on first launch, from the chat banner ("Setup needed: ... Tap to fix", red for required, amber for recommended), from Settings, and whenever Run is pressed with a required item missing (also from the overlay).

Verified on a fresh install, using the real system screens and dialogs: first launch opened the checklist ("0 of 4 ready"); **Turn on** opened the system Accessibility list and the service page; enabling it and pressing Allow returned to the checklist by itself (the service connecting brings `SetupActivity` to the front); **Allow notifications** raised the system permission dialog and the item flipped to green when it closed; **Allow** (battery) raised the "Let app always run in background?" dialog and the system whitelist then listed the app; **Model settings** opened the settings screen and the item updated on return; **Check connection** showed "Checking..." then "Reachable (12550 ms)" (the shared server was slow; slow replies over 8 s now say so). Failure paths: with the service off the chat shows the red banner and Run leads to the checklist; switching the service off mid-task stops the run immediately (audit `run_stopped reason=service_off`, chat "The accessibility service was turned off, so I stopped.", notification removed).

Lessons: system settings must open in their own task (`NEW_TASK`), otherwise a leftover system page sits inside our task and relaunching the app resumes it; returning to the checklist uses `CLEAR_TOP`. The accessibility-details action is not a public constant (literal string, falls back to the list). Android notification prompts stop appearing after two refusals, so the checklist falls back to the app's notification settings. 139 unit tests.

Unverified on a real device: the app-op check for "restricted settings" (reads as not blocking for `adb`-installed builds), and the direct accessibility-details screen on Samsung.

## Bot Lab results (2026-09-26, emulator)

`botlab-app/` (applicationId `ga.egarcia.botlab`) is an arena of instrumented views (they record how they are operated) plus freeRASP 19.3.1 (Talsec community SDK, from its own Maven repository and JitPack, scoped in `settings.gradle.kts`) and a behaviour analyzer (`analysis/`, 15 pure unit tests). Full results and method: `docs/detectability.md`. Project stance: ADR 0006, **measure and disclose; never add camouflage** (no hiding the service, no spoofed input metadata, no cloned typing/touch profiles, no fake typos, no pacing meant to look human). Do not tune the harness against the lab score; fix concrete compatibility bugs only.

Same arena, three ways of operating it: harness accessibility actions **90/100** (9 `ACTION_CLICK`, `FOCUS`, `SET_TEXT` visible via `View.performAccessibilityAction`; 8 of 8 clicks with no touch; 29 characters with no keyboard), harness gestures **75** (accessibility-gesture flag `0x800`, virtual device id, constant 50 ms taps, straight swipes), `adb input` **60**. Any app can list our enabled accessibility service (reads screen, gestures, screenshots, not a declared tool) and, with a package query, the installed-but-off service. freeRASP (production mode) flagged emulator, debuggable, sideloaded and ADB, flagged every sideloaded app in its malware scan, and did not report automation/hooks/root for the harness. A view with `accessibilityDataSensitive` is absent from our snapshot (Android 14+).

Run: `scripts/botlab_experiment.py api-actions api-gestures adb-input` (needs the harness running). Human baseline: use the arena by hand and press Save session (not done yet; do it on the Fold 4).

Lessons: (1) a swipe from the screen edge is the system home gesture, so experiment swipes must stay inside the smallest scrollable element; (2) `adb forward` can silently disappear, re-run it when the API refuses connections; (3) if every snapshot is `no_active_window` (even Settings) and logcat shows `AccessibilityManagerService: wait for adding window timeout`, the emulator's accessibility window pipeline is wedged: `adb reboot` fixes it (toggling the service does not); (4) freeRASP's `SuspiciousAppDetectionConfig` has obfuscated parameter names, so pass arguments positionally.

## Dev commands (this machine)

```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug :sample-app:assembleDebug
adb install -r android-harness/build/outputs/apk/debug/android-harness-debug.apk
adb install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
adb shell 'settings put secure enabled_accessibility_services ga.egarcia.harness/ga.egarcia.harness.HarnessAccessibilityService; settings put secure accessibility_enabled 1'
scripts/configure_llm.py --base-url http://100.94.145.51:8000/v1 --model Qwen3.6-35B-A3B-4bit   # emulator: tailnet IP
scripts/set_policy.py --overlay --notes-file notes.txt --denied pkg.or.keyword --gate
scripts/run_task.py "Use open_app to open Sample App and tell me how many messages have been sent."
```

## Next steps

1. **Real-device validation on the Fold 4** (the main open item): follow `docs/setup.md` (the checklist should guide restricted settings, notifications and battery; disable Auto Blocker if needed; enable Tailscale so `http://my-server:8000/v1` works by name), sideload both APKs, run the same tasks. Check: approval dialog and Approve/Deny notification, keep-awake, permission-dialog behavior, the floating button on both displays and when unfolding, Samsung's keyboard in the overlay prompt, lock-screen behavior.
2. Agent quality: compare `Qwen3.8-27B-4bit`, tune the prompt (it sometimes taps launcher icons instead of `open_app`), screenshot-use policy, lower-token screen format.
3. Foldable AVD (Pixel Fold definition) for layout and posture testing; recorded fixtures.
4. ExampleApp reference scenario: only with a throwaway account and the physical device.
5. Optional: MCP server for driven mode (ARC covers it today); Hermes-on-Termux + ARC comparison.

## Not in v1

ADB fallback, iOS (not portable), CLI companion, scripted plugin navigation, multi-device, scheduling, history beyond the local audit log, Play Store distribution, on-phone LLM inference.
