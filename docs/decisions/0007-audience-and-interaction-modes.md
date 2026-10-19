# ADR 0007 — Primary audience: people who need help with their phone, not power users

Status: **Proposed** · Date: 2026-10-16 · Updated 2026-10-18: guide mode has a working, live-verified PoC (see
Consequences); explain mode and do-mode's confirm-gate wording are not started.

## Context

The competitive research (`private/competitor-research.md`) found that every close competitor (android-use, Handy
Agent, OpenDroid, Deft, android-agent, ARC) targets the same buyer: someone who already wants an autonomous
agent and is evaluating how capable or safe it is. None target someone who doesn't fully trust themselves with
the phone yet. That gap was flagged as "the accessibility-assistance angle" but left as a positioning option, not
a decision — it would mean designing for that user explicitly, not adding it as a footnote.

The owner decided to make that audience primary: **elderly users, first-time smartphone/Android users, and
people with disabilities** — anyone who needs a phone operated or explained, not just automated. Concrete
examples the owner gave: *"increase my brightness,"* *"how do I send an email?"*, *"why is this message about a
crash or low storage on my screen? How do I fix it?"*. Note the range: a direct action, a how-do-I-even question,
and a read-and-explain-a-system-notification question — three different shapes of help, not one.

## Decision

1. **Primary audience is explicit, not incidental.** Requirements §1.1 names it; the README leads with it.
2. **Three interaction modes, chosen per task (by the user, or inferred when unambiguous):**
   - **Do it.** The current behavior: agent acts, confirm gate still applies to anything irreversible. Best for
     "increase my brightness."
   - **Guide me.** The agent tells the user what to tap, step by step, on their own screen, instead of acting
     itself. Best for "how do I send an email?" — the goal is the person learns the path, not that the task gets
     done once.
   - **Explain this.** Read-only: the agent reads what's on screen (a notification, a dialog, a toast) and
     explains what it means and what the person's options are, without touching anything. Best for "why is this
     message about a crash or low storage on my screen? How do I fix it?" — this is closer to the existing
     read-only digest tools (`read_notifications`) than to the acting tools.
3. **This directly affects the standing `isAccessibilityTool` question (ADR 0002).** The reason that flag has
   never been set is that Android 14+ then hides permission/installer dialogs from the service, so the agent goes
   blind and has to ask the user to handle them (`docs/decisions/0002-security-model.md`). For this audience,
   that is not a workaround to route around — **it is correct behavior**: a person should grant their own
   permissions, not have an agent silently click through consent prompts on their behalf, guide or do mode alike.
   That reframes `isAccessibilityTool="true"` from "a cost we avoid" to "plausibly the right default for this
   product," which also reopens the Play Store distribution path (readiness item 8) that sideload-only was
   permanently closing.
4. **Sequencing, cheapest first:**
   - **Explain mode** is nearly free: it is the existing read-only digest machinery (`read_notifications`,
     untrusted-content framing) pointed at whatever's currently on screen instead of only notifications.
   - **Guide mode** is mostly existing parts recombined: the floating overlay and playbooks already exist; guide
     mode is largely "show the hint to the human instead of acting on it."
   - **Do mode** already exists; no new work, but its confirm-gate messaging should read well for this audience
     (plain language, not developer-speak).
   - Nothing here requires touching `PolicyAutomation`'s enforcement; the safety layer stays enforced in code
     exactly as it is (ADR 0002), which matters *more* for this audience, not less.

## Consequences

- **Guide mode: PoC built and verified live (2026-10-18), not yet production-ready.** `GuideLoop` (observe →
  model → `point_to`/`ask_user`/`done` → wait for the user → repeat), `GuideOverlay` (highlight box + instruction
  bubble), and `GuideRunner`/an adb entry point (`RUN_GUIDE`/`STOP_GUIDE`, `scripts/run_guide.py`) exist and were
  run against a real model and a real emulator, not just unit tests. `docs/requirements.md` §3.9 has the full
  functional requirements, done and remaining, broken into independent, ticket-sized pieces.
  - **The safety property is structural, not tested-in:** `GuideLoop` takes a read-only `observe: suspend () ->
    Snapshot` lambda, never an `Automation` reference, so there is no tap/type/scroll/swipe call anywhere in its
    source — a model that ignores the tool schema and tries to act anyway gets an error, never an action
    (`GuideLoopTest.anActingToolNameIsRejectedNotExecuted`).
  - **Two bugs only a live run caught, not unit tests:** (1) the overlay's `WindowManager`/`View` calls were
    first wired up from the loop's background coroutine, which throws immediately ("`Looper.prepare()`" never
    called) — fixed by posting through a main-thread `Handler`, same pattern `OverlayController` already uses.
    (2) The highlight box rendered a consistent, exact, status-bar-height offset below the real element (pixel-
    verified against the accessibility tree's own bounds) — the standard inset-avoidance window flags
    (`FLAG_LAYOUT_IN_SCREEN`/`FLAG_LAYOUT_NO_LIMITS`, `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN`) did not change this for
    `TYPE_ACCESSIBILITY_OVERLAY` windows on the device tested; fixed by measuring and directly subtracting the
    status bar height. Both are exactly the kind of bug `docs/requirements.md`'s testing strategy already expects
    unit tests to miss (device/window-manager behavior) — see FR-70's acceptance criteria.
- **Explain mode: not started.**
- **Narrower product, on purpose.** "Any OpenAI-compatible endpoint, bring your own Ollama server" is a
  power-user setup story that doesn't survive contact with "first-time smartphone user." A simpler guided setup
  (possibly a hosted default eventually) is now in tension with the current BYO-endpoint framing and needs its
  own decision later, not resolved here.
- **Real-user validation bar goes up.** "Tested on one emulator and a Fold 4" was adequate for a personal tool;
  validating guide/explain modes needs actual sessions with elderly or first-time users, not just functional QA.
  Not scheduled yet.
- **Local text-to-speech** (flagged by the competitive research as something a competitor, Deft, has and this
  project doesn't) becomes more relevant for guide/explain modes than it was for do-only — still not committed,
  tracked here as a candidate, not a requirement.
- **The `isAccessibilityTool` decision itself is still open**, not reversed by this ADR — ADR 0002's reasoning
  about permission dialogs stays correct; what changed is whether the tradeoff is now worth taking. Decide and
  update ADR 0002 together with implementing guide/explain mode, not before.
