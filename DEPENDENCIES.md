# NWC Ring — Dependencies

**Draft, 2026-10-04. Nothing is installed or built yet.** Versions are the
current stable releases on that date and get confirmed when the first build
runs at Milestone 1. From then on this file is the record: every library in the
app, its exact pinned version, and why it is there. Nothing is added without a
line here.

## Libraries that ship inside the app

| Library | Version | Who maintains it | Why it is needed |
|---|---|---|---|
| Kotlin standard library | 2.4.20 | JetBrains | The language the app is written in |
| Kotlin coroutines | 1.11.0 | JetBrains | Doing network work without freezing the screen |
| Kotlin serialization (JSON) | 1.11.0 | JetBrains | Reading and writing the stored inventory and wallet replies |
| Jetpack Compose (BOM) | 2026.09.00 | Google | The screens |
| Activity Compose | 1.13.0 | Google | Connects the screens to the app window |
| Lifecycle | 2.11.0 | Google | Knowing when the app leaves the screen, so it can lock |
| Core KTX | 1.19.1 | Google | Basic Android helpers Compose expects |
| Biometric | 1.1.0 | Google | The fingerprint / face / PIN prompt |
| DataStore | 1.2.1 | Google | Saving the inventory file safely (no half-written files) |
| Quartz | 1.16.0 | Vitor Pamplona and the Amethyst contributors | All Nostr and wallet-connect work: keys, signing, NIP-44 and NIP-04 encryption, relay connections, NWC messages |

No library is used for encryption at rest. That is Android Keystore, which is
part of the phone.

## What arrives with Quartz

Quartz is the one dependency with a long tail. These 19 come with it. The ones
marked *unused* are not needed by anything this app does; the build strips
unused code from the finished app, and at Milestone 1 I will test whether they
can be excluded outright and report the result.

| Library | Version | What it is |
|---|---|---|
| secp256k1-kmp (Android and JVM) | 0.24.0 | ACINQ's binding to libsecp256k1, the core Bitcoin signing library. **Used: every signature** |
| OkHttp (Android, coroutines) | 5.5.0 | Square's network library. **Used: the relay connections** |
| kotlinx-crypto HMAC, SHA-2 | 0.0.4 | Hash functions. Probably used by NIP-44; confirmed at Milestone 1 |
| Kotlin stdlib, coroutines, serialization JSON | as above | Already in the app |
| AndroidX Core KTX, Collection, Compose runtime annotations | 1.19.0, 1.6.0, 1.12.1 | Small Google support libraries. Used |
| kotlinx collections-immutable | 0.5.2 | Data structure helper. Used |
| Jackson (Kotlin module) | 2.22.2 | A second JSON library. *Likely unused by the NWC path* |
| kotlinx serialization CBOR | 1.11.0 | A binary data format. *Unused* |
| AndroidX SQLite, SQLite bundled | 2.7.1 | A database engine for Quartz's event store. *Unused* |
| kmp-negentropy | 1.2.0 | Relay sync protocol. *Unused* |
| kchesslib | 1.0.5 | A chess library. *Unused* |

## Used only to build and test, never shipped in the app

The first five rows are installed on the build box as of 2026-10-05. The rest
arrive with the first build.

| Tool | Version | Why |
|---|---|---|
| Java (Eclipse Temurin JDK) | 21.0.12.1+1 | Runs the build |
| Android command-line tools | 23.0 | Installs and manages the rows below |
| Android platform tools (`adb`) | 37.0.1 | Talks to the emulator and the test phone |
| Android SDK platform | 37.0 r2 | The Android libraries the app is built against |
| Android emulator, with the Android 16 (API 36) Google APIs image r7 | 37.2.12 | Running the app and the encryption tests on this box |
| Gradle | 9.8.0 | The build system |
| Android Gradle Plugin | 9.4.1 | Builds Android apps |
| JUnit, Kotlin test, coroutines test | matching the above | The automated tests |
| OkHttp MockWebServer | 5.5.0 | The fake relay inside the test suite |

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
