# Privacy: what leaves the phone

The app has **no telemetry, analytics, crash reporting or accounts**. Data leaves the phone only in the ways below, all of
which you configure or trigger.

| What | Where it goes | When |
|---|---|---|
| Screen text (element labels), optionally a screenshot, your task, tool results | The model endpoint **you** configured (your own server, or a hosted API) | Every agent step of a task you started. Plain HTTP is allowed only to local or Tailscale hosts; other hosts need HTTPS. Screenshots are sent only if the model is marked vision-capable. |
| Notification text from apps you switched on, email sender, subject and first lines | The same model endpoint, as tool results | Only if you enabled the digest and the agent read it for a task |
| Microphone audio | **Google** (Google speech mode, unless "prefer on-device" is on and available) or nowhere (Local Whisper: processed in memory on the phone) | Only while you use the microphone button |
| IMAP login and mail | Your mail server, over TLS only | Only if you added an email account; read-only |
| Speech model file | Downloaded **from** the model repository to the phone | Only when you choose Local Whisper and confirm the download |
| Diagnostics report | Wherever **you** send it (share sheet, clipboard, `adb pull`) | Only when you press Share or Copy; never automatic |

## Stored on the phone

- API key, email password, the driven-mode token: encrypted with an Android Keystore key. Not included in backups or device transfer.
- Captured notifications (apps you chose only): encrypted, at most 200 items and 7 days.
- Activity (audit) log: what the agent did, without screen text or typed text. Session records for diagnostics: timings, tool names and outcomes; task text and model replies only if you turn on detailed records.
- Speech models: private app storage, deleted with the app or with "Delete this model".

## Not collected

Contacts, files, location, photos, other apps' data beyond what is on the screen while a task runs, and anything from the
apps and keywords on the blocked list (banking, wallets, password managers and authenticators; see ADR 0002).

The details behind each row are in the feature guides: [setup](setup.md), [inbox digest](inbox-digest.md),
[voice input](voice-input.md), [diagnostics](diagnostics.md).
