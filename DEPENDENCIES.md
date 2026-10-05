# NWC Ring — Dependencies

This file is the record: every library in the app, its exact pinned version,
and why it is there. Nothing is added without a line here. Updated at
Milestone 1, 2026-10-05.

## Libraries that ship inside the app

**As of Milestone 1 the app declares five libraries.** These are the versions in
the build.

| Library | Version | Who maintains it | Why it is needed |
|---|---|---|---|
| Kotlin standard library | 2.4.20 | JetBrains | The language the app is written in |
| Kotlin coroutines | 1.11.0 | JetBrains | Doing storage and (later) network work without freezing the screen |
| Kotlin serialization (JSON) | 1.11.0 | JetBrains | Reading and writing the stored inventory file |
| Jetpack Compose: UI and Material 3 | BOM 2026.09.00 | Google | The screens |
| Activity Compose | 1.13.0 | Google | Connects the screens to the app window |

No library is used for encryption at rest or for the unlock prompt. Both are
parts of Android itself (Android Keystore and the system biometric prompt).

**What those five bring with them.** Asking for Compose pulls in 66 separate
pieces in total: about 55 small AndroidX modules from Google (Compose's own
parts, lifecycle, core, window and similar), the JetBrains libraries above, and
three tiny annotation-only libraries (JetBrains annotations, JSpecify, and an
empty Guava placeholder). Everything is published by Google or JetBrains. The
full list can be printed with
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.

Planned in the Milestone 0 draft and then **not needed**:

| Library | Why it was dropped |
|---|---|
| AndroidX Biometric | It exists to smooth over Android versions older than 11. With Android 11 as the minimum, the phone's own prompt does everything directly |
| DataStore | The inventory is one small file. Writing a temporary file and swapping it in gives the same crash safety with no library |
| Core KTX, Lifecycle (declared directly) | Not used directly; they still arrive as part of Compose |

**Arriving at Milestone 3:** Quartz 1.16.0 (Vitor Pamplona and the Amethyst
contributors), for all Nostr and wallet-connect work: keys, signing, NIP-44 and
NIP-04 encryption, relay connections, NWC messages. The app has no network
permission until then.

## Used only to build and test, never shipped in the app

| Tool | Version | Why |
|---|---|---|
| Java (Eclipse Temurin JDK) | 21.0.12.1+1 | Runs the build |
| Gradle (via the checksum-verified wrapper) | 9.8.0 | The build system |
| Android Gradle Plugin | 9.4.1 | Builds Android apps |
| Kotlin compiler, with the Compose and serialization plugins | 2.4.20 | Compiles the app |
| Android command-line tools | 23.0 | Installs and manages the SDK pieces below |
| Android platform tools (`adb`) | 37.0.1 | Talks to the emulator and the test phone |
| Android SDK platform, build tools | 37.0 r2, 36.0.0 | The Android libraries the app is built against, and the packaging tools |
| Android emulator, with the Android 16 (API 36) Google APIs image r7 | 37.2.12 | Running the app and the encryption tests on the build box |
| JUnit | 4.13.2 | The automated tests |
| Kotlin coroutines test | 1.11.0 | Testing code that works in the background |
| AndroidX Test runner, JUnit extension | 1.7.0, 1.3.0 | Running tests on a phone or emulator |

Planned for Milestone 3: OkHttp MockWebServer, as the fake relay inside the test
suite.

## Considered and not used

| Library | Why not |
|---|---|
| nostr-sdk (`org.nostrdevkit`, formerly rust-nostr) 0.45.1 | The fallback if Quartz fails its checks. Self-described alpha; its wallet-connect helper has no `get_budget`; compiled Rust with its own networking. Full reasoning in `PLAN.md` |
| Jetpack Security (`security-crypto`) | Deprecated by Google in 2025 in favour of using Android Keystore directly |
| Tink | A good library, but with the hardware key store doing the encryption there is nothing left for it to do |
| Room (database) | A few dozen records do not need a database |
| A navigation library, a dependency-injection framework | Six screens do not need them |
| Any analytics, crash reporting or ads library | Ruled out by the project's requirements |
| A QR / camera library | Out of scope for the first version |
