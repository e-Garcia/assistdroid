## What and why

## Checklist
- [ ] Tests added or updated; `./gradlew :android-harness:testDebugUnitTest :android-harness:assembleDebug` passes
- [ ] Docs updated (guide, ADR or requirements) where behaviour changed
- [ ] No secrets, personal data or screen text added to logs, exports or fixtures
- [ ] Every screen action still goes through `PolicyAutomation`; no new way around the denylist or confirm gate
- [ ] Not an evasion feature (see `docs/non-goals-detection-bypass.md`)
- [ ] Effect on APK size noted if it changes by more than about 100 KB

## Safety / privacy impact
