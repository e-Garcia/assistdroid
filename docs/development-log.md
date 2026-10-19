# Development log

A chronological record of what was built and verified, with the lessons learned along the way (mostly on the
`Harness_API36` emulator). It is background reading, not a specification: the current design is in `docs/decisions/`
(ADRs) and `docs/requirements.md`, and the feature guides sit next to this file.

Personal details of the original author's setup (hostnames, addresses, accounts) have been replaced by placeholders such
as `my-server` and `<model-host-tailscale-ip>`.

## Spike results (2026-09-25, `Harness_API36` emulator, Settings app)

Verified: 401 without/with wrong token; snapshot merges list rows into one labelled clickable (`"Bluetooth · Connected devices > …"`); `tap`, `type`, `back`, `home` work; stale/unknown refs and non-editable targets return typed errors; bad JSON gives 400. Timing on a small screen: snapshot ~8 ms, tap ~57 ms, type ~25 ms (NFR-10..12 met, but re-measure on heavy real-world screens). 7 unit tests pass.

Run it:
```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug
adb install -r android-harness/build/outputs/apk/debug/android-harness-debug.apk
adb shell 'settings put secure enabled_accessibility_services dev.egarcia.harness/dev.egarcia.harness.HarnessAccessibilityService; settings put secure accessibility_enabled 1'
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

## Phase 2 results (2026-09-25, emulator + model server model)

Run it (the API key is read from an environment variable and never printed):
```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug   # 42 unit tests
adb install -r android-harness/build/outputs/apk/debug/android-harness-debug.apk
adb shell 'settings put secure enabled_accessibility_services dev.egarcia.harness/dev.egarcia.harness.HarnessAccessibilityService; settings put secure accessibility_enabled 1'
scripts/configure_llm.py --base-url http://<model-host-tailscale-ip>:8000/v1 --model Qwen3.6-35B-A3B-4bit   # emulator: tailnet IP
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
- **Model server contention:** the model server is shared with other jobs. One first reply took 67 s and a direct probe 17.9 s (normally 1 to 3 s). The 180 s request timeout absorbs this, but the chat should show "waiting for the model" (the step status does).
- Not yet exercised live: the follow-up flow after `ask_user` (pure parts are unit-tested) and the settings "Test connection" button and Save.

## Phase 4 results (2026-09-25, emulator + sample app)

Policy layer (`policy/`, ADR 0002 implementation status): denylist, confirm gate, password block, audit log. Set it headlessly with `adb shell am broadcast -a dev.egarcia.harness.ADB_POLICY -n dev.egarcia.harness/.AdbPolicyReceiver --es denied 'pkg.or.keyword,another' --ez gate true` (DUMP-gated); pass `--es denied "''"` to clear.

Testing trick: the driven-mode API goes through the same `PolicyAutomation`, so the gate can be tested deterministically with curl and the sample app (no model latency): `POST /api/tap` on "Send message" blocks until you press Approve/Deny on the phone. The sample app's counters prove exactly which actions ran.

Verified live: Deny gave `user_denied` and `Sent: 0`; Approve gave `ok` and `Sent: 1` (exactly once); an unanswered approval timed out after 120 s as a denial; password typing returned `blocked`; a blocked app showed `app_denied` with zero elements and refused tap/scroll/screenshot/open_app while Back worked and `wait_for` could not peek at its text; the real model, facing a blocked app, opened it, saw only `app_denied`, and asked the user instead of working around it; the audit log held no typed or screen text (`chars=19` only) and is viewable in Settings > View activity log. 98 unit tests pass.

Lessons: (1) an opaque approval dialog stops the app underneath and kills its node handles, so approved taps failed with `stale_ref`; the dialog is translucent now and the tap re-resolves the element after the app returns (waits for the original package to be in front, or the dialog closing looks like our own denied app). (2) The model server can stall for minutes under load (one probe >200 s, another 70 s, then 0.7 s); a run then ends cleanly with `llm_error: network Request timeout` after 180 s. (3) Do not trust `dumpsys window` focus checks in test scripts; log a content-free marker instead (`HarnessApproval: approval requested`).

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

`botlab-app/` (applicationId `dev.egarcia.botlab`) is an arena of instrumented views (they record how they are operated) plus freeRASP 19.3.1 (Talsec community SDK, from its own Maven repository and JitPack, scoped in `settings.gradle.kts`) and a behaviour analyzer (`analysis/`, 15 pure unit tests). Full results and method: `docs/detectability.md`. Project stance: ADR 0006, **measure and disclose; never add camouflage** (no hiding the service, no spoofed input metadata, no cloned typing/touch profiles, no fake typos, no pacing meant to look human). Do not tune the harness against the lab score; fix concrete compatibility bugs only.

Same arena, three ways of operating it: harness accessibility actions **90/100** (9 `ACTION_CLICK`, `FOCUS`, `SET_TEXT` visible via `View.performAccessibilityAction`; 8 of 8 clicks with no touch; 29 characters with no keyboard), harness gestures **75** (accessibility-gesture flag `0x800`, virtual device id, constant 50 ms taps, straight swipes), `adb input` **60**. Any app can list our enabled accessibility service (reads screen, gestures, screenshots, not a declared tool) and, with a package query, the installed-but-off service. freeRASP (production mode) flagged emulator, debuggable, sideloaded and ADB, flagged every sideloaded app in its malware scan, and did not report automation/hooks/root for the harness. A view with `accessibilityDataSensitive` is absent from our snapshot (Android 14+).

Run: `scripts/botlab_experiment.py api-actions api-gestures adb-input` (needs the harness running). Human baseline: use the arena by hand and press Save session (not done yet; do it on the Fold 4).

Lessons: (1) a swipe from the screen edge is the system home gesture, so experiment swipes must stay inside the smallest scrollable element; (2) `adb forward` can silently disappear, re-run it when the API refuses connections; (3) if every snapshot is `no_active_window` (even Settings) and logcat shows `AccessibilityManagerService: wait for adding window timeout`, the emulator's accessibility window pipeline is wedged: `adb reboot` fixes it (toggling the service does not); (4) freeRASP's `SuspiciousAppDetectionConfig` has obfuscated parameter names, so pass arguments positionally.

## Inbox digest results (2026-09-26, emulator)

`inbox/` + `InboxSettingsActivity` (guide: `docs/inbox-digest.md`). `read_notifications` and `read_email_digest` are read-only agent tools (also `GET /api/notifications`), offered only when set up; results are framed `untrusted="true"`. Notification capture is opt-in per app (`HarnessNotificationListener`, encrypted `files/notifications.enc`, denylisted apps never captured); email is IMAPS-only with read-only folder and `BODY.PEEK`, sender allowlist by real address, app password in Keystore. Setup checklist has an `Importance.OPTIONAL` item that never counts as a problem (`SetupReport.counted`). Headless: `scripts/set_policy.py --notif-apps pkg --notif-clear`. Verified live with the real model (3 steps, no app opened); email verified by 18 GreenMail-backed tests and the in-app rejection of a self-signed server, not yet against a real provider.

Lessons: (1) inside `LinearLayout.apply {}` `Button(this)` binds to the layout; use `this@Activity`. (2) `mail.imaps.ssl.checkserveridentity` is separate from `ssl.trust`; tests set both, the app never does. (3) JavaMail's `android-mail` and `android-activation` both ship `META-INF/LICENSE.md`, exclude it in `packaging`. (4) GreenMail stamps the received date itself, so age tests move the client clock instead.

## Diagnostics results (2026-09-26, emulator)

`diag/` + `DeveloperActivity` (guide: `docs/diagnostics.md`). Every agent run is recorded (`SessionRecorder` over the `AgentEvent` stream, including the new `Observed` event) with timings, tokens, redacted tool calls, outcomes and packages; optional detailed level adds task/reply text. The report (`AndroidDiagnostics.build`) adds device info, git commit (`BuildConfig.GIT_SHA`), settings without secrets, setup status, metrics, last crash (`CrashRecorder` in `HarnessApp`), audit log and own logcat, all passed through `Redactor`. Export: share sheet via `DiagFileProvider` (own ContentProvider, no androidx), clipboard, or `adb shell am broadcast -a dev.egarcia.harness.ADB_DIAG ...` then `adb pull`. Verified live: report from a real run, detailed vs default levels, share sheet target read the file (`serving ... to another app`). Lessons: (1) a wedged emulator shows as `startBlind: no_active_window` in the report, `adb reboot` fixes it; (2) the first `keepAwake` right after a service (re)connect can throw `BadTokenException` (window token not ready); logged by the report, not yet fixed.

## Keep-awake and dimming (2026-09-26, emulator)

While a run is active (`AgentRunner`): (1) `RunWakeLock` holds a partial wake lock (CPU, so model requests continue) and a `SCREEN_DIM_WAKE_LOCK` fallback (deprecated but honoured, needs only the normal `WAKE_LOCK` permission), both with a 12-minute timeout and released in `finally`; (2) the accessibility overlay window with `FLAG_KEEP_SCREEN_ON` keeps the display on and carries an optional `screenBrightness` override (Settings > Screen during tasks, default on at 20%, 2 to 60%), which Android drops by itself when the window goes; adding it is retried 4 times because right after a service reconnect the window token can be missing (`BadTokenException`, seen in the first diagnostics report); (3) while an approval is pending the brightness returns to normal (`ApprovalUi.broker.pending` observer calls `setDim(null)`). The outcome is audited as `keep_awake` (`screen`, `wake_lock`, `dim`) and the report shows `screen_off_timeout` and `dim_while_running`. Verified with a 15 s screen timeout: awake for a 50 s task (locks `harness:run` and `harness:screen` plus the system's screen-bright lock for the overlay), `mWindowManagerBrightnessOverride=0.2` during the run, `NaN` during an approval and after the run, wake locks 0 afterwards and the phone slept normally. The emulator's reported `mCurrentScreenBrightness` does not move, so the visible dimming is unverified until the Fold 4. The run refuses to start if the screen is already off or locked (unchanged).

## Voice input results (2026-09-26, emulator)

`voice/` + `VoiceSettingsActivity` (guide: `docs/voice-input.md`). Optional and off by default. Mic button above Run in the chat and left of Close in the floating prompt (`MicButton`, `VoiceInput` controller with Google `SpeechRecognizer` and Local Whisper engines). Whisper: vendored whisper.cpp v1.9.4 CPU-only (`third_party/whisper.cpp`, built by `src/main/cpp/CMakeLists.txt`, Release even in debug builds, arm64 built with `armv8.2-a+fp16+dotprod` and CPU-flag checked at runtime), tiny JNI (`jni.c`), models downloaded after enabling (`ModelDownloader`: HTTPS only, resume, pinned revision, SHA-256 verified, atomic rename; `ModelDownloads` app-scoped state). Verified: real download (59,721,011 bytes, hash OK), exact JFK transcript in 3.7 s on the x86 emulator, mic states, overlay recording with another app in front (`dumpsys audio`: silenced:false), Google recognizer starting and reporting "no match" on silence, 193+ unit tests including a socket-based download server. Not verified: real speech (emulator mic is silent), arm64 speed, Fold 4. APK: universal 10.6 MB, `-PabiOnly=arm64-v8a` 8.5 MB (6.6 MB before voice); dynamic feature delivery is not possible while sideloading (see docs). Lessons: (1) `com.sun.net.httpserver` is not on the Android unit-test classpath (use a socket server); (2) debug builds compile native code without -O3 unless `CMAKE_BUILD_TYPE=Release` is passed; (3) a `LinearLayout` column wrapping a fixed-size button squeezes the sibling button unless it has a minimum width.

## Dev commands (this machine)

```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug :sample-app:assembleDebug
adb install -r android-harness/build/outputs/apk/debug/android-harness-debug.apk
adb install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
adb shell 'settings put secure enabled_accessibility_services dev.egarcia.harness/dev.egarcia.harness.HarnessAccessibilityService; settings put secure accessibility_enabled 1'
scripts/configure_llm.py --base-url http://<model-host-tailscale-ip>:8000/v1 --model Qwen3.6-35B-A3B-4bit   # emulator: tailnet IP
scripts/set_policy.py --overlay --notes-file notes.txt --denied pkg.or.keyword --gate
scripts/run_task.py "Use open_app to open Sample App and tell me how many messages have been sent."
```

## Guide mode PoC results (2026-10-18, emulator + sample app)

`GuideLoop` + `GuideOverlay` + `GuideRunner` (ADR 0007, `docs/requirements.md` FR-70/71). Driven headlessly via
`scripts/run_guide.py` (adb broadcast `RUN_GUIDE`/`STOP_GUIDE`, same pattern as `run_task.py`), against the real
configured model, not a scripted fake, task: "How do I send a message?" against the sample app.

Verified live: the model correctly picked the Message field (`ref=e2`) from the real element list and gave a
correct plain-language instruction; the highlight box rendered at the exact pixel bounds of the real element
(checked against `uiautomator dump`'s own bounds for that node, and against a direct pixel scan of a
screenshot); after simulating the user typing ("Hello") with `adb shell input text`, the loop noticed the
screen had changed, re-queried the model, and — correctly, given the on-screen keyboard was now covering the
Send button — called `ask_user` instead of guessing a next step.

Two bugs found only by running it, not by the unit tests (which all still passed throughout):
1. `GuideOverlay.show()` initially called `WindowManager.addView` from the loop's background coroutine
   (`Dispatchers.Default`, same dispatcher `GuideRunner` runs on) and crashed with "Can't create handler inside
   thread ... that has not called `Looper.prepare()`" -- silently, from the overlay's point of view: the step
   was narrated to logcat correctly and the loop kept running, it just never drew anything. Fixed: post through
   a main-thread `Handler`, the same pattern `OverlayController` already uses.
2. The highlight box rendered a consistent, exact 132px (this device's status bar height, confirmed via
   `dumpsys window displays`) below the real element -- `TYPE_ACCESSIBILITY_OVERLAY` windows apparently still
   get their content origin offset by the status bar on this API level regardless of
   `FLAG_LAYOUT_IN_SCREEN`/`FLAG_LAYOUT_NO_LIMITS` or `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` (tried both, measured
   both, neither changed the offset). Fixed pragmatically: read the status bar height and subtract it directly;
   lint correctly flags this as using an unsupported internal resource (the WindowInsetsCompat way needs
   androidx, which this app deliberately doesn't depend on) -- suppressed with a comment, not silenced blindly.

Not covered by this PoC run, left for FR-72/73/76: any UI entry point a real user could actually reach (today
it's adb-only), a target near the screen edge, a second display, real-user sessions.

## Next steps

1. **Real-device validation on the Fold 4** (the main open item): follow `docs/setup.md` (the checklist should guide restricted settings, notifications and battery; disable Auto Blocker if needed; enable Tailscale so `http://my-server:8000/v1` works by name), sideload both APKs, run the same tasks. Check: approval dialog and Approve/Deny notification, keep-awake, permission-dialog behavior, the floating button on both displays and when unfolding, Samsung's keyboard in the overlay prompt, lock-screen behavior.
2. Agent quality: compare `Qwen3.8-27B-4bit`, tune the prompt (it sometimes taps launcher icons instead of `open_app`), screenshot-use policy, lower-token screen format.
3. Foldable AVD (Pixel Fold definition) for layout and posture testing; recorded fixtures.
4. Real-app reference scenario (private, see `docs/requirements.md` §5): only with a throwaway account and the physical device.
5. Optional: MCP server for driven mode (ARC covers it today); Hermes-on-Termux + ARC comparison.

## Not in v1

ADB fallback, iOS (not portable), CLI companion, scripted plugin navigation, multi-device, scheduling, history beyond the local audit log, Play Store distribution, on-phone LLM inference.
