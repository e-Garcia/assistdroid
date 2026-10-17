# ADR 0005 — On-device agent in one native Kotlin app

Status: **Accepted** (owner confirmed 2026-09-25) · Date: 2026-09-25 · Refines ADR 0004, updates ADR 0003

## Context

The primary goal is: the owner types a task **on the phone** and the phone operates itself to finish it (like Hermes with computer use on a PC). ADR 0004 covered the *tool server* (hands) and recommended adopting ARC for PC-driven mode, but it did not cover the on-device *agent* (brain, prompt UI, safety).

Options considered for the on-device agent:

| Option | Verdict |
|---|---|
| **A. Hermes on Termux + ARC over localhost** | Works in principle (Hermes documents Android/Termux, MCP in the tested path), but it is a best-effort "Tier 2" platform; Android may suspend Termux background jobs; needs Python 3.13 via TUR; prompting in a terminal or Telegram; two extra apps; reportedly arm64-only so it cannot be exercised on the x86_64 emulator. Keep as a **later comparison** on the physical phone. |
| **B. Existing on-device agent apps** (PrivateAgent, OpenDroid, MobileAppAgent) | Closest to the desired UX, but: PrivateAgent has **no license**, is Flutter (Dart-to-Kotlin bridges, exported receiver to re-register channels), requests SMS/call/contacts/mic permissions, has release APKs **debug-signed with no attestation** and a manual upload path (see review notes below). OpenDroid and MobileAppAgent were not run; OpenDroid has near-identical copies, MobileAppAgent is a 3-commit re-upload of a fork. **Reference reading only, nothing copied.** |
| **C. Own on-device agent, native Kotlin** | Chosen. |

Review notes for B (2026-09-25, static only): original repo `orailnoor/private-agent` (created 2026-06-26, 338 stars, no license). v1.0.2 APKs are signed with `CN=Android Debug`, no GitHub attestations, CI workflow uploads artifacts but does not publish the release (release files were uploaded by hand). Tag `v1.0.2` is one CI-only commit behind `main`. Verifying an APK against source was therefore not possible from the published artifacts; a local rebuild from the tag would be required.

## Decision

1. **One native Kotlin app** (`dev.egarcia.harness`, extending the spike) hosts: the accessibility service (automation core), the agent service, the prompt UI, the policy layer, and (optionally) the HTTP/MCP server from the spike.
2. **Kotlin, not Flutter.** The core (accessibility service, foreground service, overlay windows) must be native; a Dart layer only adds a bridge, a second runtime to keep alive in the background, more third-party native plugins, and a larger APK (spike 5.8 MB vs ~20 MB per ABI for PrivateAgent). Flutter's cross-platform benefit does not apply: iOS has no cross-app accessibility API, no overlays and no background agent, so an iOS port of this app is not possible. iOS, if ever wanted, is a separate host-driven tool server (WebDriverAgent on a Mac) behind the same MCP interface.
3. **Tools are called in-process** by the agent (no HTTP hop, no token), reusing the spike's `SnapshotBuilder` and actions behind a small `Automation` interface. The spike's HTTP server stays optional for driven mode; ARC covers PC-driven use until we need our own.
4. **Agent loop** (Kotlin coroutines, cancellable):
   - Each step: compact snapshot (latest only in full) → OpenAI-compatible chat completion with tool calling → validate → execute → post-action snapshot.
   - Limits: max steps (default 30), per-run timeout, stuck detection (same snapshot hash + same action 3 times), explicit `done` / `ask_user`.
   - Tools: `snapshot`, `screenshot` (vision models only), `tap`, `type`, `scroll`, `swipe`, `back`, `home`, `open_app`, `wait`, `done`, `ask_user`, `confirm`.
   - Screen text is wrapped as untrusted data with a system-prompt instruction to ignore instructions inside it.
5. **LLM client:** Ktor client (already a dependency) to any OpenAI-compatible base URL; API key wrapped with an Android Keystore key; vision flag per model. Cleartext HTTP is allowed only to local or tailnet hosts (localhost, RFC 1918, Tailscale 100.64.0.0/10, `.ts.net`/`.local`/`.lan`, single-label names); everything else needs HTTPS. This is enforced by `CleartextPolicy` in the client, because Ktor's CIO engine does not go through Android's network security config.
6. **Prompt UI:** v1 chat screen (task box, live step log, result, history) plus a foreground service with an ongoing notification carrying a **STOP** action. v1.1: floating overlay bubble via an accessibility overlay window (no extra overlay permission). Voice later.
7. **Safety is enforced in code, not by the model** (ADR 0002 applies):
   - App denylist and password-field masking in the tool layer.
   - **Confirm gate:** tap/type on send/post/submit/delete/purchase/call/SMS targets is blocked until the user approves on screen. Heuristics on button text and playbook markers, plus the model's own `confirm` tool.
   - Draft-only by default; results are returned to the user.
   - Local audit log of each step (time, package, action, short model rationale). No screenshot storage, no telemetry.
   - Egress only to the configured LLM endpoint, with opt-in for non-local endpoints.
8. **Plugins become playbooks (refines ADR 0003):** per-app hint text and optional state recognizers stored on the device and injected into the prompt while that package is foreground. Scripted navigation paths are deferred. Playbooks can only narrow behaviour, never bypass the denylist or confirm gate.
9. **Run constraints:** device unlocked and awake; the agent pauses and asks on keyguard/biometric/permission prompts and never tries to bypass secure locks; the user should not use the phone during a run.
10. **One controller at a time:** the on-device agent and any external MCP client must not drive the phone simultaneously (documented; enforced by a run lock).

## Owner decisions (2026-09-25)

- **LLM:** an OpenAI-compatible server (**omlx** in the reference setup): `http://my-server:8000/v1` on the model server, reached over **Tailscale**, key from Hermes' `OMLX_API_KEY`, `repetition_penalty: 1.1` as an extra body field. Default model `Qwen3.6-35B-A3B-4bit` (also Hermes' default); the server also lists `Qwen3.8-27B-4bit`, `Qwen3.6-27B-4bit`, `Qwen3.5-9B-OptiQ-4bit`. **Verified (2026-09-25):** tool calling and image input both work on the 35B-A3B; with `chat_template_kwargs: {"enable_thinking": false}` a tool call is ~27 completion tokens and a vision answer takes ~1 s, so thinking is off by default (config flag). The dense 27B was much slower (12 to 22 s per call) and truncated at 300 tokens with thinking on; compare its quality later.
- **Emulator vs phone networking:** the emulator cannot resolve MagicDNS short names, so use the Tailscale IP (100.x) there; the Fold 4 with the Tailscale app can use `my-server`.
- **Test device:** Samsung Galaxy Z Fold 4 (arm64). Ships with Android 12L and can be updated; check the installed version.
- **Prompt UX:** chat screen first, floating overlay later.
- **Network:** the phone reaches the PC through the Tailscale app. Traffic is WireGuard-encrypted, so plain HTTP to a tailnet address is acceptable; the network security config allows cleartext only for explicitly configured hosts.

## Fold 4 and Samsung considerations (to verify on the device)

- **Two displays and posture changes.** The cover screen (about 6.2") and the inner screen (about 7.6", nearly square) differ in size, density and aspect ratio, and the app is resized when unfolding. Refs, not coordinates, are already the interface; the agent loop must re-snapshot after a fold or unfold, and test on a foldable AVD (Pixel Fold device definition) before the phone.
- **Floating button (implemented).** Accessibility overlay windows (bubble, prompt, status pill, result card); focusable only while idle so the app being operated keeps focus. Verify on the Fold: placement across the cover and inner displays and after folding or unfolding, and the Samsung keyboard.
- **Multi-window.** Split screen and pop-up windows are common on a Fold. The snapshot currently reads only the active window; multi-window support (all windows, focused first) is a follow-up.
- **Sideloading friction (One UI).** Android 13+ blocks accessibility for sideloaded apps until "Allow restricted settings" is enabled in the app's info page. I believe Samsung's Auto Blocker can also block installs from unknown sources and USB commands; check and turn it off for development.
- **Background limits.** Samsung's battery management ("Sleeping apps", "Deep sleeping apps") can kill background services; the harness must be excluded (NFR-30).
- **Android version.** The Fold 4 can run Android 13+; confirm the installed version (relevant to ARC's minSdk 33 and to screenshots, which need API 30+).

## Testing

- Unit: `SnapshotBuilder` fixtures (exists), loop logic, stuck detection, confirm-gate heuristics.
- **Fake OpenAI-compatible server** (scripted tool calls) for deterministic agent-loop tests, first on the JVM then on the emulator.
- Sample app with known screens, dialogs, toasts and lists for emulator runs. The real-app reference scenario (private, see `docs/requirements.md` §5) stays gated (throwaway account + physical device).
- Emulator-only trigger for early runs: `adb shell am start ... --es task "..."` (debug builds), before the UI exists.

## Phases

1. Automation core behind an `Automation` interface; add `scroll`, `swipe`, `wait`, `open_app`, `screenshot`.
2. LLM client + agent loop + fake-server tests; run on the emulator via the debug trigger.
3. Chat screen, foreground service, STOP.
4. Policy: denylist, confirm gate, audit log.
5. Overlay bubble; playbooks.
6. Optional: MCP server (reuse spike) if ARC is not enough.
7. Later comparison: Hermes on Termux + ARC on the physical arm64 phone.

## Consequences

- No dependency on Termux, Python or a second APK for the primary use case; the spike code is reused.
- We own the agent quality (prompting, context pruning, recovery), which is the real work.
- Compact snapshots keep token cost low for small/local models.
- The physical phone must be unlocked and unused during runs.
- ADR 0004's "adopt ARC" now applies to driven mode only; FR-40..49 (agent loop) are back in scope.

## Open questions

- Which model/provider first; tool-calling reliability of local models and whether a JSON-action fallback is needed.
- ~~Keep-awake mechanism during a run.~~ Resolved: a 1x1 untouchable `TYPE_ACCESSIBILITY_OVERLAY` window with `FLAG_KEEP_SCREEN_ON` kept the emulator awake past its screen timeout while a task ran, and released on stop (controlled experiment, 2026-09-25). No wake lock or extra permission. Still to confirm on the Fold 4.
- Reliable send/post/delete/buy detection across apps and languages.
- Physical device Android version and ABI.
