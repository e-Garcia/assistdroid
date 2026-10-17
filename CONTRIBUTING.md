# Contributing

Thanks for helping. This project drives other apps on a person's phone, so it is stricter than most about safety,
privacy and honesty. Please read this page and the [Code of Conduct](CODE_OF_CONDUCT.md) first.

> This project is MIT-licensed (see [LICENSE](LICENSE)). No contribution terms (DCO or CLA) exist yet; until they do, please
> open issues and discuss designs, and hold off on large pull requests. See [open-source readiness](docs/open-source-readiness.md).

## Set up

JDK 17, Android SDK (API 36), NDK 28.2.13676358 and CMake 3.22.1 (install from SDK Manager), `adb`. Then:

```bash
./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug :sample-app:assembleDebug
```
Dependencies are checksum-verified (`gradle/verification-metadata.xml`); after changing one, regenerate it with `./gradlew --write-verification-metadata sha256 <the CI tasks> --refresh-dependencies` and review the diff.
Unit tests need no device. For live checks use an emulator with the sample app; never test against real accounts or apps
that hold money or credentials. `-PabiOnly=arm64-v8a` builds a phone-only APK.

## Workflow

1. Open an issue for anything non-trivial and say what you plan to change.
2. Branch from `main` (`feat/...`, `fix/...`, `docs/...`), keep changes focused, add tests, update the docs that describe the behaviour.
3. Run the full test task above; CI runs the same.
4. Open a pull request using the template. Explain *why*, note any effect on the safety layer, privacy or the APK size.

Commit messages: an imperative summary line, then a paragraph on why. Style is in `.editorconfig`; write code that reads like its
neighbours (plain Views, no new AndroidX or Compose dependency without an ADR, pure logic in classes that need no Android).

## Rules that are not negotiable

These come from the [ADRs](docs/decisions/) and protect users; changes touching them need an ADR update and extra review.

- **Everything goes through `PolicyAutomation`.** No new path may act on the screen around the denylist, the confirm gate or the password rule.
- **Untrusted text stays untrusted.** Screen, notification and email text is framed as data for the model and never appears in logs or in the default diagnostics report.
- **No secrets in output.** API keys, tokens and passwords are never logged, exported or put in commit history.
- **Do not set `isAccessibilityTool`** on the service.
- **No camouflage.** Pull requests that hide the service or package, spoof input metadata or device identity, clone a person's typing or touch behaviour, add fake typos, or randomise timing to look human will be closed. See [non-goals](docs/non-goals-detection-bypass.md). Narrow compatibility fixes (for example a fixed pace because one app drops characters) are welcome when documented.
- **Opt-in for anything that reads more.** New data sources (like notifications or email) are off by default, read-only, allowlisted by the user and documented.

## Reporting bugs

Use the issue template and attach the diagnostics report (Settings > Developer > Share report). Read it first: the default
level contains no screen text, but package names, tool calls and timings are included. Do not paste API keys or personal data.
Security problems go to [SECURITY.md](SECURITY.md), not to public issues.
