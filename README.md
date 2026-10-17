# AI Harness

An Android app that lets an AI agent operate your phone. You type (or dictate) a task on the phone, an
OpenAI-compatible language model chooses the next tap, scroll or text entry, and the app carries it out through
Android's accessibility service, behind a safety layer that asks you before anything irreversible.

> **Status: experimental.** Built for personal use first, on one emulator and one phone model; the design and the
> safety model are documented, but expect rough edges. See [Open-source readiness](docs/open-source-readiness.md)
> for what's done and what's still open before the first public release.

<p>
  <img src="docs/images/chat.png" width="270" alt="Chat screen after a finished task">
  <img src="docs/images/floating-prompt.png" width="270" alt="Floating prompt over another app">
</p>

## What it does

- **On-device agent:** observe the screen, ask the model for one tool call, act, repeat. Screen text, screenshots (for vision models) and tools stay in the app; only the model endpoint you configure receives them.
- **Any OpenAI-compatible endpoint** with tool calling: a server on your own network (Ollama, llama.cpp, vLLM, LM Studio, omlx, ...) or a hosted API. Plain HTTP is allowed only to local or Tailscale hosts.
- **Safety layer** (see [ADR 0002](docs/decisions/0002-security-model.md)): apps that hold money or credentials are blocked; send, post, delete, buy and call taps wait for your approval on screen; password fields are never typed into; an audit log records what happened without recording screen or typed text; a Stop button is always one tap away.
- **Read without operating apps:** an opt-in notification and email digest lets the agent summarise messages without opening a messaging app ([guide](docs/inbox-digest.md)).
- **Voice input** (optional, off by default): Google speech or a Whisper model that runs on the phone and is downloaded only if you enable it ([guide](docs/voice-input.md)).
- **Floating button**, per-app **playbooks** (notes that tell the agent how an app works), a guided **setup checklist**, and a **Developer** screen that exports a redacted diagnostics report.
- **Bot Lab** (`botlab-app`): a test tool that shows what an app can observe about how it is being operated. The project's stance is to *measure and disclose*, never to camouflage ([ADR 0006](docs/decisions/0006-detectability-measure-not-camouflage.md), [non-goals](docs/non-goals-detection-bypass.md)).

## What it is not

- Not a way to evade apps' automation checks, and not a tool for bulk or covert activity. Automating an app can violate its terms of service; that risk is yours.
- Not an iOS project (iOS has no equivalent cross-app accessibility API).
- Not published on Google Play (Play restricts autonomous use of accessibility services); it is sideloaded.

## Try it

Requirements: JDK 17, Android SDK with NDK 28.2 and CMake 3.22 (SDK Manager), an emulator (Google APIs image, API 34+) or a phone.

```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug :sample-app:assembleDebug
adb install -r android-harness/build/outputs/apk/debug/android-harness-debug.apk
adb install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
```

Open **AI Harness**: the setup checklist walks you through turning on the accessibility service (Android 13+ needs
"Allow restricted settings" first), the notification and battery settings, and your model (base URL, model name, API key).
Then try: *"Open the Settings app and tell me the Android version."* Details and Samsung notes: [docs/setup.md](docs/setup.md).

From a computer, `scripts/` has adb helpers (configure the model, run a task, set policy). A driven mode (local HTTP API
behind a bearer token, reached through `adb forward`) is described in [docs/requirements.md](docs/requirements.md).

## How it is built

Native Kotlin, plain Android Views (no Compose or AndroidX in the main app), Ktor for the model client and the optional
local server, whisper.cpp (vendored, CPU only) for on-device speech. `Automation` is the interface the agent uses;
`AccessibilityAutomation` implements it and `PolicyAutomation` wraps it so **every action passes through the safety layer**.
Start with [docs/requirements.md](docs/requirements.md) and the [ADRs](docs/decisions/); the history is in
[docs/development-log.md](docs/development-log.md).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) (including what will not be accepted) and [SECURITY.md](SECURITY.md).
An accessibility service can read the screen and act as you, so report vulnerabilities privately.
Third-party licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

MIT — see [LICENSE](LICENSE).
