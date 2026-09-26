# Setup guide

The app checks what it needs and walks you through it. Open **Setup checklist** from the chat banner ("Setup needed: … Tap to fix"), from **Settings**, or automatically on first launch. Each item shows a status and a button that opens the exact system screen or permission dialog. When you come back, the list updates itself, and after you switch the accessibility service on, the app returns you to the checklist.

| Item | Needed? | What it does | If it is missing |
|---|---|---|---|
| **Accessibility service** | Required | Lets the agent read the screen and tap, type and scroll for you. | Tasks cannot start. Turn on "AI Harness" in Accessibility settings. |
| **AI model** | Required | The model address, name and key. "Check connection" tests it. | Tasks cannot start. A model that cannot be reached shows a warning with the reason (is Tailscale on?) but does not block you from trying. |
| **Notifications** | Recommended | The Stop button, "agent is working" status, and Approve/Deny requests while the agent is in another app. | Approval dialogs still appear over the app, but you lose the Stop notification. |
| **Background battery use** | Recommended | Keeps long tasks from being cut off when the phone puts apps to sleep. | Tasks may stop half-way on aggressive battery managers (Samsung especially). |

## Enabling the accessibility service (Android 13+, sideloaded builds)

Android blocks accessibility services of apps installed outside an app store until you allow it:

1. In the checklist, tap **Allow restricted settings**. This opens the app's info page.
2. Tap the **⋮** menu (top right) and choose **Allow restricted settings**. (The menu only appears after you have tried to switch the service on once; if you do not see it, go back, tap **Turn on**, dismiss the "Restricted setting" message, and return here.)
3. Back in the checklist, tap **Turn on**, select **AI Harness**, switch **Use AI Harness** on and confirm with **Allow**.

The app then brings you back to the checklist. The system's own confirmation ("Allow AI Harness to have full control of your device?") is the consent for what the service can do; read it.

## Samsung (One UI), for the Galaxy Z Fold 4

- **Auto Blocker** (Settings > Security and privacy > Auto Blocker) can stop apps installing from outside the Galaxy Store and block `adb` over USB. Turn it off while you sideload.
- **Battery:** besides the "Allow" button, make sure the app is not under Settings > Battery > Background usage limits > *Sleeping apps* or *Deep sleeping apps*.
- **Restricted settings** works as above; the ⋮ menu is in Settings > Apps > AI Harness.

## Tailscale (model on your computer)

The phone needs the Tailscale app connected to the same tailnet as the computer running the model. Then use the computer's tailnet name (for example `http://my-server:8000/v1`). If "Check connection" fails, open Tailscale on the phone first.

## While a task is running

If you switch the accessibility service off, the task stops immediately with "The accessibility service was turned off, so I stopped." If the phone locks, the task stops and asks you to unlock it.

## For developers

- Rules live in `setup/SetupChecker.kt` (pure, unit-tested); the Android side is `setup/AndroidSetup.kt`; the screen is `SetupActivity`.
- Not yet verified on a real device: the app-op check that detects "restricted settings" is blocking (it reads as not blocking for `adb`-installed builds), and whether the direct accessibility-details screen opens on Samsung (the app falls back to the accessibility list).
- Never set `isAccessibilityTool` on the service: it would expose system permission dialogs to it (see ADR 0002).
