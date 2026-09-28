# Open-source readiness

Where the project stands on the way to a public repository, and the decisions that are still open.
**No license has been chosen**, on purpose; that decision comes just before release.

## Done

| Area | State |
|---|---|
| Secrets and personal data | The working tree and the full git history (38 commits, 1 author) were scanned for API keys, tokens, private keys and secret-shaped strings: none found. Personal hostnames, addresses and agent names were replaced by placeholders in code, tests, docs and scripts; machine-specific notes live in an ignored `CLAUDE.local.md`. `scripts/configure_llm.py` no longer defaults to a personal env file. |
| Community files | `README.md`, `CONTRIBUTING.md` (including what will not be accepted), `SECURITY.md`, `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), issue and PR templates, Dependabot, `.editorconfig`, `.gitattributes`. |
| CI | `.github/workflows/ci.yml`: unit tests, debug builds of all three apps, Android lint; actions pinned to commit SHAs; read-only token; Gradle wrapper validated. Not run yet (no remote). |
| Supply chain | Gradle dependency verification (`gradle/verification-metadata.xml`, SHA-256 for 516 components) passes a refreshed full build. Vendored whisper.cpp is pinned, unmodified, CPU-only (`third_party/whisper.cpp/VENDORED.md`). Speech models are downloaded on demand, pinned to a revision and hash-checked. |
| Licenses of dependencies | `THIRD_PARTY_NOTICES.md`, generated from the resolved dependency graph and POM license fields. Everything is Apache-2.0, MIT, or (JavaMail/Activation) EPL-2.0 or GPL-2.0 with Classpath Exception. |
| Code health | Android lint is clean (0 issues, a few justified suppressions). Lint found real bugs that are fixed: notification parsing used APIs newer than `minSdk` 26 (would have crashed on Android 8 to 10), the approval dialog ignored the back gesture on Android 13+, and the floating bubble was not operable by accessibility tools. Added a launcher icon and backup exclusion rules. 213 unit tests. |
| Docs | Documentation index, privacy statement (`docs/privacy.md`), ADR statuses brought up to date, long `CLAUDE.md` split into a short one plus `docs/development-log.md`. |

## Decisions still open

1. **License.** Constraints and options:
   - All dependencies are permissive or Classpath-Exception licensed, so any of the common choices is possible.
   - **Apache-2.0** (recommended default): permissive, explicit patent grant, matches Kotlin, Ktor and most dependencies.
   - **MIT**: simplest and matches whisper.cpp; no patent grant.
   - **GPL-3.0 / AGPL-3.0**: copyleft, keeps forks open; still compatible with the dependencies (not GPL-2.0-only, because of Apache-2.0 components).
   - A license cannot forbid misuse and remain open source; the safety posture is enforced in code and in `CONTRIBUTING.md` instead.
   - When chosen: add `LICENSE`, license headers or SPDX identifiers if wanted, update `README.md`, `CONTRIBUTING.md`, `THIRD_PARTY_NOTICES.md`.
2. **Package name and branding.** The author's real domain is `egarcia.dev`, not `ga.egarcia.harness`'s implied `.ga`; going forward, reverse-DNS from `egarcia.dev` (`dev.egarcia.<app>`) is the intended convention for the author's open-source Android apps, ahead of the older, inconsistent `com.egarcia.myfriendz` (MIT, copyright "e-Garcia", no domain tie). Several candidate project names have been checked against existing projects and ruled out or shortlisted; that research names other people's projects, so it is kept out of this repository (private, gitignored — see `CLAUDE.local.md` for where). Renaming later breaks in-place upgrades of installed builds, so decide the name and package together before the first public release (mechanical: package directories, manifest, scripts, docs).
3. **Repository home and contacts.** Owner or organisation, name, visibility; a security contact and a conduct-enforcement contact (placeholders marked `TODO` in `SECURITY.md`, `CODE_OF_CONDUCT.md`, issue-template config); enable private vulnerability reporting, branch protection and required CI.
4. **Contribution terms.** DCO sign-off or a CLA (or neither). Decide together with the license.
5. **Git history.** Publish as is, or start from a squashed history. The tree was scanned and is clean of secrets and personal data (see the row above), **but two commits added and then removed a competitor-research document** (naming other projects) before it was moved to the private, untracked `private/` folder; that content is still recoverable from history. If publishing with history intact, either accept that or rewrite history to drop it (`git filter-repo` or an interactive rebase) before the repository goes public.
6. **What to ship.** Bot Lab (`botlab-app`) depends on freeRASP and a device-fingerprinting library and exists to *measure*; keep it in the repo, or move it to its own repository. The reference scenario in `docs/requirements.md` and the example in `docs/playbooks.md` name a specific professional network whose terms prohibit automation; consider making that scenario generic before publishing.
7. **Release engineering.** Signing key (kept offline), release build type with shrinking, versioning and a changelog, GitHub Releases with `SHA256SUMS` and build provenance attestation, optionally an SBOM (CycloneDX). Debug-signed builds are for testing only.
8. **Support statement and distribution.** Sideloaded, not on Google Play; tested on one emulator image and awaiting real-device validation (Samsung Galaxy Z Fold 4). This is now more than a general restriction: a Play policy effective January 1, 2026 (enforced from January 28, 2026) specifically prohibits Accessibility API use that lets an app "autonomously initiate, plan, and execute actions" — exactly this project's architecture — unless the app is a declared accessibility tool (`isAccessibilityTool="true"`) whose autonomy serves people with disabilities ([Play Console policy](https://support.google.com/googleplay/android-developer/answer/16550159), [Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)). That is in direct tension with the standing rule in `CLAUDE.md` never to set `isAccessibilityTool` (chosen so Android 14+ doesn't hide sensitive system dialogs from the service; ADR 0002). Decide at release time: stay sideload-only (no change needed), or design a narrower, genuinely disability-focused accessibility-tool product, which would need its own design and a different `isAccessibilityTool` trade-off.

## Before the first public release (checklist)

- [ ] Choose the license; add `LICENSE`; update README and notices
- [ ] Decide the project/package name (see the private naming research); rename if needed
- [ ] Decide the distribution path (sideload-only vs. an accessibility-tool product for Play) per item 8 above
- [ ] Fill in the contact placeholders (`grep -rn "TODO before release" .`)
- [ ] Create the repository; push; confirm CI is green (first real run of `ci.yml`)
- [ ] Enable private vulnerability reporting, Dependabot, branch protection
- [ ] Validate on a real phone (setup, approval dialog, keep-awake, floating button, voice, digest)
- [ ] Human baseline in Bot Lab; review `docs/detectability.md` for accuracy
- [ ] Regenerate `THIRD_PARTY_NOTICES.md` and `gradle/verification-metadata.xml`
- [ ] Re-run the secret and personal-data scan on the final history
- [ ] Tag, build a release APK, publish with checksums and provenance
