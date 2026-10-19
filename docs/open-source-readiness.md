# Open-source readiness

Where the project stands on the way to a public repository, and the decisions that are still open.

## Done

| Area | State |
|---|---|
| License | **MIT** (see `LICENSE`); `README.md`, `CONTRIBUTING.md` and `THIRD_PARTY_NOTICES.md` updated accordingly. |
| Secrets and personal data | The working tree and the full git history (38 commits, 1 author) were scanned for API keys, tokens, private keys and secret-shaped strings: none found. Personal hostnames, addresses and agent names were replaced by placeholders in code, tests, docs and scripts; machine-specific notes live in an ignored `CLAUDE.local.md`. `scripts/configure_llm.py` no longer defaults to a personal env file. |
| Real-app ToS exposure | The original reference scenario (exact navigation steps, UI text, package name) named a real company's app and how to automate it against its terms. Moved to the private, gitignored `private/real-app-reference-scenario.md`; public docs now describe the scenario generically and use a placeholder app/package in examples; the name itself is also gone from all of git history (see item 5 below). |
| Community files | `README.md`, `CONTRIBUTING.md` (including what will not be accepted), `SECURITY.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), issue and PR templates, Dependabot, `.editorconfig`, `.gitattributes`. |
| CI | `.github/workflows/ci.yml`: unit tests, debug builds of both apps, Android lint; actions pinned to commit SHAs; read-only token; Gradle wrapper validated. Not run yet (no remote). |
| What to ship | Bot Lab split into its own repository, [`e-Garcia/botlab`](https://github.com/e-Garcia/botlab) — it had its own dependency risk (freeRASP, a device-fingerprinting library) unrelated to the harness itself. `docs/detectability.md`'s results stay here since they're part of the safety story; its "how to run it" section now points at the separate repo. |
| Supply chain | Gradle dependency verification (`gradle/verification-metadata.xml`, SHA-256 for 516 components) passes a refreshed full build. Vendored whisper.cpp is pinned, unmodified, CPU-only (`third_party/whisper.cpp/VENDORED.md`). Speech models are downloaded on demand, pinned to a revision and hash-checked. |
| Licenses of dependencies | `THIRD_PARTY_NOTICES.md`, generated from the resolved dependency graph and POM license fields. Everything is Apache-2.0, MIT, or (JavaMail/Activation) EPL-2.0 or GPL-2.0 with Classpath Exception. |
| Code health | Android lint is clean (0 issues, a few justified suppressions). Lint found real bugs that are fixed: notification parsing used APIs newer than `minSdk` 26 (would have crashed on Android 8 to 10), the approval dialog ignored the back gesture on Android 13+, and the floating bubble was not operable by accessibility tools. Added a launcher icon and backup exclusion rules. 213 unit tests. |
| Docs | Documentation index, privacy statement (`docs/privacy.md`), ADR statuses brought up to date, long `CLAUDE.md` split into a short one plus `docs/development-log.md`. |

## Decisions still open

1. ~~**License.**~~ Resolved: MIT.
2. ~~**Package name and branding.**~~ Resolved: the package is `dev.egarcia.*` (matching the author's real domain `egarcia.dev`, renamed from the old `ga.egarcia.*`), and the project itself is named **AssistDroid**. Candidate names and the collision research that named other people's projects stay in the private, gitignored `private/competitor-research.md` (see `CLAUDE.local.md`); the final check that cleared AssistDroid (direct GitHub, Play Store and web search, not just AI-assisted search) is not repeated here since it doesn't name a real collision. The package stays `dev.egarcia.harness` regardless of the project name — the two are independent by convention, so this doesn't need another mechanical rename.
3. **Repository home and contacts — mostly resolved.** `github.com/e-Garcia/assistdroid` and `github.com/e-Garcia/botlab` both exist, pushed, CI green on both. Contact placeholders in `SECURITY.md`, `CODE_OF_CONDUCT.md` and the issue-template config filled with `erick@egarcia.dev`. Dependabot alerts are on for both. **Still open: both repos are private, not public** — see the checklist below for why that's the one step left for a human. Branch protection and private vulnerability reporting need public visibility first (GitHub returned "upgrade to Pro or make public" / 404 while private).
4. **Contribution terms.** DCO sign-off or a CLA (or neither). Decide together with the license.
5. ~~**Git history.**~~ Resolved: rewritten with `git filter-repo`. The competitor-research commits, the Bot Lab
   subtree (split to its own repo) and every mention of the real-app ToS exposure's target company (content and
   commit messages) were removed from
   all history, not just the working tree. A full, unmodified mirror of the pre-rewrite history is kept privately
   (`~/Backups/android-ai-harness-FULL-HISTORY-backup-*.bundle`) in case it's ever needed — nothing was actually
   lost, it's just not in the history that goes public.
6. ~~**What to ship.**~~ Resolved: Bot Lab split into its own repository.
7. **Release engineering.** Signing key (kept offline), release build type with shrinking, versioning and a changelog, GitHub Releases with `SHA256SUMS` and build provenance attestation, optionally an SBOM (CycloneDX). Debug-signed builds are for testing only.
8. **Support statement and distribution — reopened by ADR 0007.** Sideloaded, not on Google Play; tested on one emulator image and awaiting real-device validation (Samsung Galaxy Z Fold 4). A Play policy effective January 1, 2026 (enforced from January 28, 2026) prohibits Accessibility API use that lets an app "autonomously initiate, plan, and execute actions" unless the app is a declared accessibility tool (`isAccessibilityTool="true"`) whose autonomy serves people with disabilities ([Play Console policy](https://support.google.com/googleplay/android-developer/answer/16550159), [Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)). That used to be in direct tension with the standing rule never to set `isAccessibilityTool` (Android 14+ then hides sensitive dialogs from the service; ADR 0002). [ADR 0007](decisions/0007-audience-and-interaction-modes.md) (2026-10-16) makes the disability-focused product the primary direction, which reframes that tradeoff as plausibly worth taking rather than one to avoid — **but the flag itself is still not set and the decision is not final.** Whoever picks this up: decide it together with implementing guide/explain mode, and update ADR 0002 when you do.

## Before the first public release (checklist)

- [x] Choose the license; add `LICENSE`; update README and notices — MIT
- [x] Rename the package to the `dev.egarcia.*` convention
- [x] Decide the project name — **AssistDroid**
- [ ] Decide the distribution path (sideload-only vs. an accessibility-tool product for Play) per item 8 above
- [x] Fill in the contact placeholders — `erick@egarcia.dev`
- [x] Create the repositories, push, confirm CI is green — [`e-Garcia/assistdroid`](https://github.com/e-Garcia/assistdroid) and [`e-Garcia/botlab`](https://github.com/e-Garcia/botlab), both green on a real GitHub Actions run
- [x] Both repos public, both confirmed unchanged from the last push (CI green, same commit) — [`e-Garcia/assistdroid`](https://github.com/e-Garcia/assistdroid), [`e-Garcia/botlab`](https://github.com/e-Garcia/botlab)
- [x] Enable private vulnerability reporting, Dependabot alerts, branch protection on `main` (required status check: `build`) — done on both repos
- [ ] Validate on a real phone (setup, approval dialog, keep-awake, floating button, voice, digest)
- [ ] Human baseline in Bot Lab; review `docs/detectability.md` for accuracy
- [ ] Regenerate `THIRD_PARTY_NOTICES.md` and `gradle/verification-metadata.xml`
- [ ] Re-run the secret and personal-data scan on the final history
- [ ] Tag, build a release APK, publish with checksums and provenance
