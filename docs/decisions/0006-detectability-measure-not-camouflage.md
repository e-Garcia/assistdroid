# ADR 0006: Detectability: measure and disclose, do not camouflage

Status: **Accepted** · Date: 2026-09-26

## Context

Apps and anti-abuse SDKs can observe how they are operated: accessibility actions on their views, injected-touch metadata, text that appears without a keyboard, and the presence of an accessibility service. The owner asked whether the harness is detectable, then whether it should learn the owner's typing and touch patterns, replay them, make deliberate typos and corrections, and vary its pace, so it passes as the person.

## Decision

1. **Measure.** The Bot Lab test app (its own repository as of 2026-10-16; results in `docs/detectability.md`) reports what an app can observe about the harness, using platform APIs and freeRASP. Findings are documented, not hidden.
2. **Do not camouflage.** The harness will not: hide or disguise its accessibility service or package; spoof or strip input metadata; profile a person's typing, touch or motion patterns to replay them; insert fake typos, corrections or hesitations; or randomize pacing to look human. Their only purpose is to make automation pass as a person, which is the behaviour anti-abuse and fraud systems exist to catch (and what banking trojans do). Doing it on the owner's own account does not change what the capability is.
3. **Fix real compatibility bugs.** If a specific app drops characters, mishandles instant text or ignores accessibility clicks, we fix that specific problem with the least behaviour change that works, and record why. That is a bug fix, not a disguise.
4. **Operate transparently.** The agent reads and drafts; a person approves send/post/delete/buy/call (ADR 0002); apps holding money or credentials are blocked. Where an app's terms forbid automation, the options are to not automate it, use an official API or export, or accept the account risk knowingly (throwaway account, low volume, human approval). The owner's decision, not something the harness tries to defeat.

Full scope, reasoning and a decision checklist: [non-goals-detection-bypass.md](../non-goals-detection-bypass.md).

## Consequences

- The harness is openly detectable, and some apps may restrict accounts that use it. This is documented rather than engineered around.
- We do not tune the harness against the Bot Lab score. The lab is a measurement and a compatibility check.
- Reviewing a future request for input "humanization" starts with the question: what functional bug does it fix, in which app?
