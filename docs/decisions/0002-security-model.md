# ADR 0002 — Security model

Status: **Accepted and implemented** (see "Implementation status" below) · Date: 2026-09-25

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

## Implementation status (2026-09-25, phase 4)

Implemented as `PolicyAutomation`, a decorator around `Automation`, so it protects both the on-device agent and the driven-mode API; the model cannot bypass it.

- **Denylist (item 3):** built-in packages and keywords (this app, password managers, authenticators, wallets/payments, anything containing "bank") plus owner entries edited in Settings or over `adb`. A blocked app is shown to the model as `BLIND: app_denied` with no elements; tap, type, scroll, swipe, screenshot and launch are refused; `wait_for` runs over the redacted screen so it cannot probe a blocked app; Back and Home always work. Three blocked snapshots in a row end the run with an explanation.
- **Sensitive content (item 4):** typing into password fields is refused outright (stronger than masking).
- **Audit log (item 5):** JSON lines in app storage, viewable and clearable in Settings. Records time, run, event, tool, package and result. Never records screen text, screenshots, typed text (length only) or the task text (length only). Gated taps record the short button label and the decision. Logcat lines redact typed text, questions, summaries and model prose too.
- **No auto-send (item 8):** the confirm gate. A tap on an element whose label (text, description or resource id, English and Spanish) means send, post, delete, buy, pay or call suspends the agent and asks the user on screen (dialog over the app plus an Approve/Deny notification). Denial, dismissal or 120 s of silence all count as refusal, and the model is told not to retry. The gate can be switched off in Settings; that is logged.
- **Approval mechanics learned the hard way:** an opaque dialog stops the app underneath and kills its node handles, so approved taps failed with `stale_ref`. The dialog is now translucent, and after approval the tap waits for the original app to be in front again and re-resolves the same element (by role, text, description, id) on a fresh snapshot.
- **Kill switch (item 6):** unchanged (STOP notification/button, accessibility toggle).

**Phase 5a additions (2026-09-26):**

- **Coordinate-tap bypass closed.** The `swipe` tool takes raw coordinates, and a zero-length swipe on the Send button pressed it with no approval (verified: `Sent: 1` on the pre-fix build). Swipes must now start inside a scrollable element of the current screen and be at least 150 px long; otherwise `blocked`. Same attack after the fix: `blocked`, `Sent: 0`.
- **Lock screen (FR-49):** the run refuses to start on a locked or sleeping phone, and stops with a clear message if the phone locks mid-task, before any further model call. The agent never tries to get past a lock.
- **Permission and installer prompts:** taps on Allow/Install-style buttons inside the system permission controller or package installer are gated (`PERMISSION`), while refusing (Don't allow, Cancel) is not. On Android 14+ (verified on API 36.1) the system hides the permission dialog from accessibility services that are not declared accessibility tools, so the agent sees `no_active_window` and cannot tap it at all. Do **not** set `isAccessibilityTool` on this service. The blind-screen message tells the model to ask the user to handle the dialog.
- **Model errors:** transient failures (network, timeouts, 408/429/5xx) are retried twice (3 s, 10 s) before the run stops; auth and request errors are not retried.

Known limits: label-based risk detection misses unlabeled icon buttons and IME "send" keys (the agent has no IME-action tool); the built-in banking list is heuristic (keyword "bank" plus named apps); a blocked app launched by a label that matches nothing is opened briefly before its screen is redacted; biometric prompts and other system surfaces are only covered when the platform hides them or the package matches the permission/installer list.

**Inbox digest additions (2026-09-26):** the notification listener and the IMAP client are new read paths, so they follow the same rules. Both are opt-in and off by default; captured notifications come only from apps the owner switched on and never from denylisted ones (this app, password managers, wallets, banking, owner entries), are encrypted with the Keystore and expire after 7 days; email is TLS-only with verified certificates, opened read-only, with the app password encrypted. Neither tool can write anything, and their output is framed as untrusted content, so an injected instruction in a message still meets the confirm gate and the denylist. Details: `docs/inbox-digest.md`.

## Consequences

- Slightly more setup (copy the token into Hermes config).
- Distribution stays sideload-only; Google Play restricts autonomous AccessibilityService use, revisit before any store release.
- Defers mutual TLS; token + localhost/tailnet is adequate for personal use.
