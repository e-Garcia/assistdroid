# Security policy

An accessibility service can read the screen and act as the user, so vulnerabilities here matter more than usual.

## Reporting

**Please report privately**, not in a public issue.

- Use GitHub's "Report a vulnerability" (private security advisory) on [the repository](https://github.com/e-Garcia/assistdroid/security/advisories/new), or
- e-mail **erick@egarcia.dev**.

Include what you found, how to reproduce it, the version (commit) and Android version. You will get an acknowledgement
within a few days; please allow reasonable time to fix before disclosing.

## What counts

- A way for the model, a web page, a notification or an email to make the app **act without the user's approval** (bypass of the confirm gate, denylist, password rule or the run lock).
- Exposure of the API key, the driven-mode token, the email password or captured notifications.
- The local HTTP API reachable or usable without the bearer token, or bound wider than intended.
- Diagnostics or logs that contain screen text, typed text, or secrets.
- A downloaded model or update accepted without its integrity check.
- Anything that lets another app on the phone read the app's private data or exported components.

Not in scope: a person with the unlocked phone and the app's permissions doing what the app is designed to do, and apps
detecting or refusing automation (see [detectability](docs/detectability.md)).

## Design

The threat model and controls are in [ADR 0002](docs/decisions/0002-security-model.md). Defaults are safe on purpose:
mandatory token, localhost-only server, blocked app list, confirm gate, encrypted secrets (Android Keystore), TLS-only mail,
pinned and hash-checked model downloads.

## Supported versions

Only the latest commit on `main` until the first tagged release.
