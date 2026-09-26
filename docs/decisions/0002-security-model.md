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

## Implementation status (2026-09-25, phase 4)

Implemented as `PolicyAutomation`, a decorator around `Automation`, so it protects both the on-device agent and the driven-mode API; the model cannot bypass it.

- **Denylist (item 3):** built-in packages and keywords (this app, password managers, authenticators, wallets/payments, anything containing "bank") plus owner entries edited in Settings or over `adb`. A blocked app is shown to the model as `BLIND: app_denied` with no elements; tap, type, scroll, swipe, screenshot and launch are refused; `wait_for` runs over the redacted screen so it cannot probe a blocked app; Back and Home always work. Three blocked snapshots in a row end the run with an explanation.
- **Sensitive content (item 4):** typing into password fields is refused outright (stronger than masking).
- **Audit log (item 5):** JSON lines in app storage, viewable and clearable in Settings. Records time, run, event, tool, package and result. Never records screen text, screenshots, typed text (length only) or the task text (length only). Gated taps record the short button label and the decision. Logcat lines redact typed text, questions, summaries and model prose too.
- **No auto-send (item 8):** the confirm gate. A tap on an element whose label (text, description or resource id, English and Spanish) means send, post, delete, buy, pay or call suspends the agent and asks the user on screen (dialog over the app plus an Approve/Deny notification). Denial, dismissal or 120 s of silence all count as refusal, and the model is told not to retry. The gate can be switched off in Settings; that is logged.
- **Approval mechanics learned the hard way:** an opaque dialog stops the app underneath and kills its node handles, so approved taps failed with `stale_ref`. The dialog is now translucent, and after approval the tap waits for the original app to be in front again and re-resolves the same element (by role, text, description, id) on a fresh snapshot.
- **Kill switch (item 6):** unchanged (STOP notification/button, accessibility toggle).

Known limits: label-based risk detection misses unlabeled icon buttons and IME "send" keys (the agent has no IME-action tool); the built-in banking list is heuristic (keyword "bank" plus named apps); a blocked app launched by a label that matches nothing is opened briefly before its screen is redacted; keyguard/lock-screen handling is not implemented yet.

## Consequences

- Slightly more setup (copy the token into Hermes config).
- Distribution stays sideload-only; Google Play restricts autonomous AccessibilityService use, revisit before any store release.
- Defers mutual TLS; token + localhost/tailnet is adequate for personal use.
