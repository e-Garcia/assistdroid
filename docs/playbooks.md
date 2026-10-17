# App notes (playbooks)

Notes you write about how a specific app works. While that app is in front, the agent sees your notes next to the screen, so it can find things faster and follow your conventions. Edit them in **Settings > App notes**, or from a computer with `scripts/set_policy.py --notes-file notes.txt`.

## Format

One entry per app. An entry starts with the app's package name, a colon, and the note. Extra lines continue the same entry. Lines starting with `#` are comments.

```
# ExampleApp (see the private reference scenario in requirements.md §5; the real entry is gated behind a
# throwaway account and a physical device, and is kept out of this public repo)
com.example.messagingapp: Messaging is the bottom tab labelled "Messaging".
  Unread conversations show a dot or bold name; the newest is at the top.
  To draft a reply, open the thread and type in the box "Write a message". Never tap Send: return the draft with done.

dev.egarcia.sampleapp: This is a fake messaging app used for tests.
  When asked for any count, reply with exactly COUNT=<number> and nothing else.
```

- The key must look like a package (`letters.and.dots`). Text such as `Note:` inside a note does not start a new entry.
- **Matching:** the exact package or the longest matching package prefix. `com.google.android` matches every Google app unless a longer entry exists, e.g. `com.google.android.gm`.
- **Length:** each note is cut at 1,500 characters. Keep them short; they cost tokens on every step.
- Find a package name with `adb shell pm list packages | grep name`, or from the agent's activity log.

## What notes can and cannot do

- They are **trusted guidance** from you, shown in a separate `<app_notes>` block. Text on the screen stays untrusted.
- They **never loosen the safety rules.** Blocked apps stay blocked, risky taps still ask you, password fields stay off-limits, whatever a note says. Those rules are enforced in code around every action, not by the model.
- Blind screens (a blocked app, a system dialog) get no notes.

## Verified example

Same task ("Open Sample App and tell me how many messages have been sent") on the emulator: without notes the answer was `0 messages have been sent.`; with the sample-app note above it was exactly `COUNT=0`.
