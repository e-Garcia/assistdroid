# Diagnostics: exporting logs and session data

When something goes wrong on a device, export a report and paste or attach it so the problem can be read
instead of described. Settings > Developer > **Developer: export logs and session data**.

## What is recorded

Every agent task is recorded automatically (last 50 runs, `files/diagnostics/sessions.jsonl`, JSON lines):

| Per run | Per step |
|---|---|
| start time, duration, result (`done`, `needs_user`, `stopped` + reason), steps, task length, model retries, token totals, model time, the app and element count at the start | model latency, tokens in/out, the tool called (typed text, questions and summaries replaced by their length), tool time, outcome (`ok`, `error: stale_ref`, `blocked: app_denied`, `user_denied`, `timeout`), the app in front afterwards, element count, blind reason, snapshot time |

**Detail level** (Developer screen, off by default) additionally keeps the task text, the run's final answer, the
model's replies and the first 300 characters of each tool result. Tool results can contain message text (for
example from `read_notifications`), so turn it on only for a run you are about to share.

Never recorded, at any level: screenshots, screen contents, typed text, API key, token, passwords.

## The report

One text file, `harness-diagnostics-YYYYMMDD-HHMMSS.txt`, with sections:

1. **Environment**: app version and **git commit**, Android version/patch, device, display, memory, battery, whether accessibility is connected.
2. **Settings (no secrets)**: model name, endpoint kind (`tailscale, http, port 8000, ip`; never the address), flags, counts of blocked apps, notes, watched apps.
3. **Setup checklist** status.
4. **Metrics** across the recorded runs: outcomes, stop reasons, model latency p50/p95/max, tokens per step, snapshot time, tool call counts, tool problems, blind screens.
5. **Sessions**, one JSON line per run with its steps.
6. **Last crash**: stack trace of the last uncaught exception (`CrashRecorder`).
7. **Audit log**, newest 300 entries (already free of screen and typed text).
8. **Logcat** of this app only (about 800 lines; Android does not let apps read other apps' logs).

Before it is written, the whole report is passed through a redactor that masks the API token, the model API
key and the email password by value, plus bearer tokens, `api_key`/`token` assignments, `sk-...` shapes and
`Authorization` headers. Debug builds log the driven-mode token at start-up, which is why this matters.
If a report exceeds its size cap it drops the oldest logcat lines first, then audit entries, then the oldest sessions.

## Getting it off the phone

- **Share report**: Android share sheet (Nextcloud, Drive, email, messaging). Served through a private
  read-only `ContentProvider` (`DiagFileProvider`, not exported, temporary grant per share, bare file names only).
- **Copy report to clipboard**: capped at 100k characters (the clipboard limit is about 1 MB); paste it into the chat.
- **Save report on the phone**: writes to `Android/data/ga.egarcia.harness/files/diagnostics/` (newest five kept).
  From a computer: `adb pull /sdcard/Android/data/ga.egarcia.harness/files/diagnostics/`.
- **Delete recorded sessions, logs and reports** clears everything above (including the activity log).

## From adb

```bash
adb shell am broadcast -a ga.egarcia.harness.ADB_DIAG -n ga.egarcia.harness/.AdbDiagReceiver [--ez detailed true|false] [--ez clear true]
adb pull /sdcard/Android/data/ga.egarcia.harness/files/diagnostics/
```
Without `clear` it writes a report and logs its path (`adb logcat -s HarnessDiag`). The receiver needs the DUMP permission, so only adb can call it.

## Reading a report (quick guide)

- `startBlind: no_active_window` on many runs: the phone's accessibility windows are not available (on the emulator, `adb reboot`); on a real phone check the service is on and no secure screen is in front.
- `stopped` with `stuck`, `max_steps`, `no_tool_call`: agent-quality problems; see the step list for the last tools and outcomes.
- `llm_ms_p95` in the tens of seconds or `model_retries` > 0: the model server is busy or unreachable (Tailscale?).
- `tool_problems` like `type: error: stale_ref` or `tap: blocked`: the screen changed under the agent, or the safety layer stopped an action.
- `Last crash` not `(none)`: send the report as is.
