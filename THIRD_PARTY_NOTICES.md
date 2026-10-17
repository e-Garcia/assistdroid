# Third-party notices

This file lists what the project builds on. It is generated from the resolved dependency graph and the licenses declared in each
package's POM (regenerate before every release; see `docs/open-source-readiness.md`). The project's own license is **MIT** (see `LICENSE`).

## Vendored source

| Component | Version | License | Where |
|---|---|---|---|
| whisper.cpp (with ggml) | v1.9.4 | MIT (Copyright (c) 2023-2026 The ggml authors) | `third_party/whisper.cpp` (CPU backend only, unmodified; see `VENDORED.md`) |

## Downloaded at the user's request (not distributed with the app)

| Component | License | Notes |
|---|---|---|
| OpenAI Whisper model weights, ggml-quantised by the whisper.cpp project | MIT | Fetched from the whisper.cpp model repository (pinned revision, SHA-256 verified) only if the user enables Local Whisper. |

## Libraries in the app (`android-harness`, `sample-app`)

| Package | Version | License |
|---|---|---|
| `com.charleskorn.kaml:kaml` | 0.79.0 | Apache-2.0 |
| `com.charleskorn.kaml:kaml-jvm` | 0.79.0 | Apache-2.0 |
| `com.squareup.okio:okio` | 3.12.0 | Apache-2.0 |
| `com.squareup.okio:okio-jvm` | 3.12.0 | Apache-2.0 |
| `com.sun.mail:android-activation` | 1.6.7 | EPL-2.0 OR GPL-2.0 with Classpath Exception (JavaMail); EDL-1.0 (Activation) |
| `com.sun.mail:android-mail` | 1.6.7 | EPL-2.0 OR GPL-2.0 with Classpath Exception (JavaMail); EDL-1.0 (Activation) |
| `com.typesafe:config` | 1.4.9 | Apache-2.0 |
| `io.ktor:ktor-client-cio` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-client-cio-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-client-core` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-client-core-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-events` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-events-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-http` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-http-cio` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-http-cio-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-http-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-io` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-io-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-network` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-network-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-network-tls` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-network-tls-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-openapi-schema` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-openapi-schema-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-serialization` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-serialization-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-serialization-kotlinx` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-serialization-kotlinx-json` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-serialization-kotlinx-json-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-serialization-kotlinx-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-auth` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-auth-api-key` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-auth-api-key-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-auth-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-cio` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-cio-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-content-negotiation` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-content-negotiation-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-core` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-core-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-csrf` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-csrf-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-routing-openapi` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-routing-openapi-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-sessions` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-server-sessions-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-sse` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-sse-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-utils` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-utils-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-websocket-serialization` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-websocket-serialization-jvm` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-websockets` | 3.6.0 | Apache-2.0 |
| `io.ktor:ktor-websockets-jvm` | 3.6.0 | Apache-2.0 |
| `it.krzeminski:snakeyaml-engine-kmp` | 3.1.1 | Apache-2.0 |
| `it.krzeminski:snakeyaml-engine-kmp-jvm` | 3.1.1 | Apache-2.0 |
| `net.thauvin.erik.urlencoder:urlencoder-lib` | 1.6.0 | Apache-2.0 |
| `net.thauvin.erik.urlencoder:urlencoder-lib-jvm` | 1.6.0 | Apache-2.0 |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-reflect` | 2.3.21 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.3.21 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-datetime` | 0.8.0-0.6.x-compat | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-datetime-jvm` | 0.8.0-0.6.x-compat | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring` | 0.9.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm` | 0.9.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-core` | 0.9.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-io-core-jvm` | 0.9.1 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-io` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-io-jvm` | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.11.0 | Apache-2.0 |
| `org.slf4j:slf4j-api` | 2.0.19 | MIT |

Notes:
- **JavaMail / Jakarta Activation (`com.sun.mail`)** are offered under EPL-2.0 or GPL-2.0 with the Classpath Exception; the Classpath Exception permits linking from a project under any license. Keep this in mind when choosing the project license.
- **`org.jetbrains.kotlin`, `kotlinx`, Ktor** are Apache-2.0.

