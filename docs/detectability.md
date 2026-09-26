# Detectability of the harness

What can an ordinary app tell about how it is being operated? The **Bot Lab** test app (`botlab-app/`) measures this with checks any app can run, plus a real RASP SDK (freeRASP by Talsec, community edition). It answers "what does our harness reveal?", not "will ExampleApp (or a bank) act on it?". Server-side decisions (account age, action rates, content, IP and device reputation) cannot be tested from a phone, and each vendor's real thresholds are private.

**Project stance (ADR 0006):** we measure and disclose. We do not hide the accessibility service, spoof input metadata, clone a person's typing or touch profile, or fake human errors and pacing to pass anti-abuse checks.

## How to run it

```bash
./gradlew :botlab-app:assembleDebug && adb install -r botlab-app/build/outputs/apk/debug/botlab-app-debug.apk
scripts/botlab_experiment.py api-actions api-gestures adb-input   # harness must be running; prints one report per mode
```
For a **human baseline**, open Bot Lab, use the arena by hand for a minute (taps, typing, scrolling, swiping) and press *Save session*. Compare with the automated runs, and use it to check that the analyzer does not flag real fingers. Sessions are JSON plus a text report in the app's `files/sessions/` (`adb shell run-as ga.egarcia.botlab cat files/sessions/<label>.report.txt`).

Use it on an emulator or test phone only: freeRASP is a third-party SDK with its own network activity.

## Results (2026-09-26, Android 16 emulator, same arena in every run)

| Way of operating the arena | Score | What the app could see |
|---|---|---|
| **Harness, accessibility actions** (tap by ref, set text, scroll) | **90** | 9 `ACTION_CLICK`, a `FOCUS` and a `SET_TEXT` delivered to its views (any app can override `View.performAccessibilityAction`); 8 of 8 clicks with no touch before them; a 29-character message appeared at once with no keyboard events |
| **Harness, touch gestures** (`dispatchGesture`) | **75** | the touches carry the accessibility-gesture flag (`0x800`) and a virtual device id; constant pressure and size; every tap exactly 50 ms; same spot each time; ruler-straight swipes |
| **`adb shell input`** (reference, not a person either) | 60 | virtual device id, zero-length taps, straight swipes, keystrokes 14 ms apart |

Scores are this lab's own heuristic (0 to 100; 45+ reads "likely automated"), for comparing modes. They are not any vendor's number.

**Environment, visible to any app with no special permission:**

- Our accessibility service is listed as enabled, with "reads screen", "performs gestures", "takes screenshots" and "not a declared accessibility tool". It is also visible when merely installed but switched off (a package-visibility query for the accessibility-service intent).
- The harness package is visible if the app declares it, along with whether it was sideloaded and whether it is debuggable.

**freeRASP** (production mode; a debug-mode run skips many checks): flagged the emulator, the debuggable build, the sideloaded install and ADB. Its malware scan flagged **every sideloaded app** (the harness, the sample app and the lab itself) with reason `installSource`; it also matched the harness on `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, but only because that permission was in the rule list we gave it. It did **not** report UI automation, hooks or root for the harness. That is one SDK version on one emulator; other SDKs and real devices may differ.

**What apps can hide from us:** a view marked `accessibilityDataSensitive` (Android 14+) is missing from the harness snapshot while the rest of the screen is readable (verified: the protected "Secret code" text was absent, its section heading present). The harness treats missing content as missing. It never tries to get around it.

## What this means

- The two things that stand out are **structural, not statistical**: an app can be told directly when an accessibility service acts on its views (`performAccessibilityAction`), and injected gestures carry metadata a finger does not. Timing and pattern statistics are secondary, and any real judgement about them lives in a vendor's model and on a server.
- Any app that wants to know can tell that this phone runs an accessibility service that reads the screen and performs gestures. That cannot be changed from inside the harness, and we do not try.
- So: expect that an app which forbids automation may notice it. The design already leans the right way: the agent reads and drafts, a person approves anything that sends, posts, deletes or buys, and apps that hold money or credentials are blocked. Prefer official APIs or exports where they exist, and keep automation off platforms whose terms prohibit it, or accept the account risk knowingly on a throwaway account.

## Limits

Client-side only; one emulator; one freeRASP version; heuristics with illustrative thresholds; no human baseline recorded yet (do it on the Fold 4 to check for false positives); the phone was not held by anyone, so motion-sensor signals are not measured.
