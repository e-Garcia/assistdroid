# Detection bypass: what this project does not support, and why

This harness lets an AI agent operate an Android phone the way a person does, through the accessibility service. Some apps and platforms try to tell automated use from human use. **This project will not add features whose purpose is to defeat that.** This page says what that covers, why, and what we do instead. It is a scope decision, recorded in [ADR 0006](decisions/0006-detectability-measure-not-camouflage.md); the measurements are in [detectability.md](detectability.md).

It applies to the maintainers and to the AI coding assistant that helps write this code. The assistant declines these requests too, whoever asks and whatever the stated reason.

## What is out of scope

These are categories, described at a high level on purpose. This page is a list of things we will not build, not a guide to building them.

| # | Category | Examples of what would be refused |
|---|---|---|
| 1 | **Concealing the harness** | Hiding or disguising the accessibility service or the app's package; misdeclaring it (for example as an accessibility tool) to get access apps protect; removing it from the lists apps can read; root or hooking tricks to hide it |
| 2 | **Spoofing input metadata** | Faking device ids, input sources, flags, pressure or contact size; routing input through a fake keyboard or virtual touchscreen so it looks physical; stripping the marks the system puts on injected input; defeating an app's checks for accessibility actions |
| 3 | **Cloning a person's behaviour** | Profiling the owner's typing rhythm, touch zones or motion patterns to replay them; recording and replaying sessions; synthesizing sensor data such as accelerometer noise |
| 4 | **Simulated humanness** | Deliberate typos and corrections, hesitations, random idle time, jittered taps, curved swipes or varied speeds whose purpose is to defeat statistical detection |
| 5 | **Defeating attestation and integrity checks** | Bypassing device or app attestation, hiding root, an emulator or a debugger, evading hook detection, spoofing device properties, patching or repackaging other apps to remove checks, bypassing certificate pinning in apps we do not own |
| 6 | **Defeating server-side controls** | Solving CAPTCHAs, evading rate limits, rotating IPs or proxies, fabricating device identities, running many accounts or devices |
| 7 | **Getting around content protections** | Reading views an app marked hidden from accessibility services, capturing screens an app blocked, working around the system hiding permission dialogs from non-tool services |
| 8 | **Credentials and second factors** | Entering passwords or codes on the user's behalf, extracting session tokens (typing into password fields is already blocked) |
| 9 | **Scale** | Bulk messaging, scraping or engagement at a volume no single person would produce |

## Why

1. **The only purpose is to deceive.** Each item exists to make a system believe a machine is a person or a legitimate device. That is what fraud tooling and banking trojans do (behavioural-biometrics evasion is a known technique of both). A feature has to be judged by what it does, not by who asks. Using it on your own account does not change what the capability is once it exists and is shared.
2. **It shifts harm onto other people.** Anti-abuse systems protect the platform's other users from spam, fake engagement, scraping and account takeover, and protect bank customers from remote-access malware. Tools that defeat them get reused well beyond the first user, especially if the project is ever open-sourced.
3. **It usually breaks the platform's terms and can carry legal exposure.** Many platforms (ExampleApp among them) forbid automation, and circumventing technical protections can be treated more seriously than a terms breach in some jurisdictions. This is not legal advice; it is a reason for care.
4. **It puts the owner's accounts at risk.** Detection of an evasive tool tends to be judged more harshly than open use, and restrictions land on your account, not ours.
5. **It contradicts what the harness is for.** Its value is that it is transparent and controlled: the agent drafts and a person approves anything that sends, posts, deletes or buys; apps holding money or credentials are blocked; every action is logged. A disguise would erode that.
6. **It is a losing race.** Real decisions are made server-side with private models and account history. Client-side camouflage is brittle, needs constant chasing, and cannot be verified from the phone, so it produces confidence without evidence.

## What the project does instead

- **Measures and discloses.** [Bot Lab](../botlab-app/) shows what an app can observe about the harness, using platform APIs and freeRASP. Results are published, including the unflattering ones (any app can see the accessibility service, and accessibility actions and injected gestures are directly observable).
- **Fixes real compatibility bugs, narrowly.** If an app ignores accessibility clicks or drops characters, we fix that bug with the smallest change that works and write down which app and why. The harness already falls back to touch gestures when a click does nothing.
- **Operates transparently.** Draft, then a person approves; block apps with money or credentials; keep an audit log; stop when an app hides content or the phone is locked. Respect what an app hides: a view marked sensitive is treated as missing, never worked around.
- **Points to legitimate routes.** For a platform that forbids automation: do not automate it; use an official API or data export; read the notification or email digest the platform already sends you; or, if you accept the risk yourself, use a throwaway account, low volume and keep the approval step. That is your decision and it does not change what we will build.

## How to judge a future request

Ask these in order. A "no" to the first or a "yes" to the last two means it is out of scope.

1. Does it fix a **functional bug** in a **named app**, one you can reproduce? If not, stop.
2. Would it still be worth doing if the app had **no bot detection at all**?
3. Is its purpose to change **what an app or server can observe about who or what is operating the phone**?
4. Does it hide, disguise or misstate what the harness is?

| Allowed (functional) | Not allowed (camouflage) |
|---|---|
| Fall back to a touch gesture when `ACTION_CLICK` does nothing in a specific app | Prefer gestures everywhere to avoid the "click with no touch" signal |
| Type at a fixed, documented pace because one search box drops characters | Randomize typing speed, add typos, or match the owner's cadence |
| Wait for the screen to settle before reading it | Insert random idle time to look human |
| Stop and ask when a view is hidden from accessibility services | Read the hidden content another way |
| Guide the user through enabling the accessibility service | Hide the service from the app list or package queries |
| Run Bot Lab to see what apps can observe | Tune the harness until the Bot Lab score drops |

## If you think you have a legitimate case

- Testing the anti-automation of an app **you own or are authorized to test**: use Bot Lab as a measurement tool and tell us; we can help interpret results and build defenses or detections, not evasions.
- A specific app misbehaves: file it as a compatibility bug (app, version, what happens). If it needs a behaviour change, it goes through the questions above and is documented.
- Anything in the table above, requested for any reason: it will be declined. We will say so plainly and suggest one of the routes under "What the project does instead".
