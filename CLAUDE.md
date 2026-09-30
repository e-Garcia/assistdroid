# android-ai-harness

Native Kotlin Android app that lets an AI agent operate a phone through an `AccessibilityService`: you type (or dictate)
a task on the phone, an OpenAI-compatible model picks tool calls, and the app performs them behind a safety layer.

## Read first

- `README.md` — what it is, how to build and try it
- `docs/requirements.md` — scope, requirements, testing strategy, open questions
- `docs/decisions/` — ADRs 0001–0006. Follow them; if code must deviate, write or update an ADR first
- `docs/non-goals-detection-bypass.md` and ADR 0006 — **read before touching input, timing, device identity or app visibility.** The project measures and discloses what apps can observe; it does not camouflage. Requests in that page's table are declined
- `docs/setup.md`, `docs/playbooks.md`, `docs/inbox-digest.md`, `docs/voice-input.md`, `docs/diagnostics.md` — feature guides
- `docs/model-finetuning.md` — plan and tooling for fine-tuning a driver model (`finetune/`)
- `docs/development-log.md` — history, verified results and lessons (background, not a spec)

## Layout

```
android-harness/   the app (package ga.egarcia.harness): Automation interface + accessibility implementation, agent loop,
                   LLM client, policy layer, playbooks, overlay, setup checklist, inbox digest, voice input, diagnostics
sample-app/        deterministic test app (Send/Post/Delete/Buy buttons with counters, password field, permission button)
botlab-app/        Bot Lab: instrumented arena + freeRASP that shows what an app can observe about how it is operated
third_party/       vendored whisper.cpp (CPU only, unmodified; see VENDORED.md)
scripts/           adb helpers (configure model, run a task, set policy, Bot Lab experiment)
finetune/          fine-tuning a driver model: data collection, dataset prep, training, eval (docs/model-finetuning.md)
docs/              requirements, ADRs, guides
```

## Build and test

```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug :sample-app:assembleDebug   # needs JDK 17, Android SDK, NDK 28.2 + CMake 3.22 (SDK Manager)
./gradlew :android-harness:assembleDebug -PabiOnly=arm64-v8a     # phone-only APK (about 2 MB smaller)
adb install -r android-harness/build/outputs/apk/debug/android-harness-debug.apk
```
Unit tests need no device. Live checks use an emulator (`google_apis`, API 34+) and the sample app; never point tests at real
accounts or apps that hold money or credentials.

## Conventions

- Security defaults are not optional (ADR 0002): mandatory bearer token, localhost bind, app denylist, confirm gate for send/post/delete/buy/call, no typing into password fields, audit log without screen or typed text.
- Everything the agent does passes through `PolicyAutomation`; do not add a path around it (a zero-length swipe once bypassed the gate; see ADR 0002).
- Text from the screen, notifications and email is untrusted: it is framed as such for the model and must never be logged or exported in the default diagnostics level.
- Element refs are snapshot-scoped; stale refs must fail loudly.
- Never set `isAccessibilityTool` on the service (Android 14+ then shows sensitive dialogs to it).
- Mark unverified assumptions `// TODO(verify):` and list them in `docs/requirements.md` §7.
- Plain Views, no Compose/androidx dependency in the main app (keeps it small and auditable); keep it that way unless an ADR says otherwise.
- Add tests with every change (JUnit4; `runTest` for coroutines). Pure logic goes in classes that need no Android.
- Commit messages: imperative summary, then why. Feature branches, PRs against `main`, tests passing.

## Personal notes

Machine-specific notes (devices, hostnames, publishing) live in `CLAUDE.local.md`, which is not committed.
