# NWC Ring — Plan

**Milestone 1 is built and tested on the emulator (2026-10-05); the test on
Reed's Pixel 6a is next.** Installed on the Pixel 6a on 2026-10-08; first
feedback is in decisions 9 and 10. Versions and library facts below were looked up on 2026-10-04.

## Decisions

Settled by Reed on 2026-10-05.

| # | Decision | Outcome |
|---|---|---|
| 1 | Which Nostr library | **Quartz**, pinned to one release. See "The Nostr library" below |
| 2 | Oldest Android version supported | **Android 11.** Reed's phones are a Pixel 6a (the test phone) and a Pixel 8 (daily), both far newer |
| 3 | Package name | **`com.nwcring.app`**, named for the project rather than for Reed. Background below |
| 4 | License | **MIT** |
| 5 | Backup | **No backup of secrets.** An export of the inventory only (names, wallets, "used in", no secrets) comes later |
| 6 | How test builds reach the phone | **Installed straight onto the Pixel 6a over USB from this box.** GitHub Releases from Milestone 5, which is how the Pixel 8 and everyone else gets it |
| 7 | The signing key | Created on this box at Milestone 1, stored outside the repo. Reed keeps an offline copy and its password; exact steps come with the key |
| 8 | Emulator and test phone | **Both.** The emulator runs the automated tests unattended. The Pixel 6a is the real thing: a real secure chip and a real fingerprint sensor |
| 9 | How the app unlocks | **NWC Ring gets its own PIN, set on first open**, with fingerprint as a shortcut. Decided 2026-10-08 after trying Milestone 1 on the phone: using the phone's own unlock as the app's unlock is not what wallet-type apps do, and whoever has the phone PIN should not automatically have this app too. Design in Milestone 2 below |
| 10 | How a connection string gets in | **A paste-into text field as well as the paste button.** Decided 2026-10-08. The field is treated like a password field so the keyboard does not learn from it. Goes into Milestone 2 |

Defaults in force unless Reed objects: auto-lock after 60 seconds idle and
immediately when the app leaves the screen; clipboard cleared 60 seconds after
a copy; relay addresses that are not encrypted (`ws://`) are refused.

**Wallets to test against at Milestone 3:** Alby Hub, Minibits, Primal.

### The package name

A package name is the app's permanent ID on Android, written like a web address
backwards. Almost nobody sees it: it shows in the phone's "App info" screen and
in app-store web links. Android uses it to tell apps apart, so two apps with the
same ID cannot both be installed, and an app can never change its ID without
becoming a different app to every phone that has it.

The convention is to build it from a web domain you own, which guarantees nobody
else picks the same one. Neither `reedbtc.com` nor `nwcring.com` is registered
by anyone (checked 2026-10-05), so both options below are equally "unowned"
today.

Reed chose `com.nwcring.app`: it names the project, the same way the OnlyBoosts
license does, and stays true if the project is ever handed to someone else.
Registering `nwcring.com` would make the name properly his; the app works the
same either way.

It can still be changed freely until the first public release.

## The stack, in plain terms

- **Kotlin and Jetpack Compose.** The standard way to write an Android app
  today. One app, one window, about six screens.
- **Android Keystore** for the encryption key. This is the phone's own secure
  hardware; the key is created inside it and cannot be copied out.
- **Quartz** for everything Nostr: keys, signing, the two encryption schemes,
  talking to relays, and the wallet-connect message formats.
- **No accounts, no server, no analytics, no crash reporting.** The only
  permission the app asks for is internet access.

The full list with versions is in `DEPENDENCIES.md`.

## The Nostr library

The brief suggested the Rust library's Kotlin bindings. I checked both
candidates and recommend the other one.

**Quartz** is the Nostr library inside Amethyst, the long-established Android
Nostr client. Release 1.16.0, 2026-09-17, MIT.

- For: it already knows the things this app is about. It has `get_budget`, the
  full list of wallet error codes (including the one that means "revoked"), and
  it reads the wallet's info event including the encryption and extensions
  tags. All of that is in the release we would pin, which already reflects the
  August change to the spec.
- For: it is Kotlin all the way down, so the exact code that handles a secret
  can be read and reviewed.
- Against: **it is a whole-Nostr library and it drags a tail with it**: 19
  libraries arrive with it, including a database engine, a JSON library and,
  oddly, a chess library. Unused code is stripped out of the finished app, but
  each one is still a supplier we are trusting.
- Against: its message encryption (NIP-44) is its own Kotlin code rather than a
  widely shared crypto library. Signing, the part that matters most, uses the
  same core Bitcoin library as everything else (libsecp256k1, via ACINQ's
  binding, the Phoenix wallet team).
- Against: it moves fast, and much of the recent work on its wallet-connect code
  is AI-written commits made after the last release. We pin one release and
  read the changes before ever moving to a newer one.

**nostr-sdk** (formerly rust-nostr, now published as `org.nostrdevkit`).
Release 0.45.1, 2026-08-19, MIT.

- For: one library instead of 19, with the cryptography in widely used Rust
  code.
- Against: its own README says "ALPHA state … the API will change in breaking
  ways".
- Against: its wallet-connect helper has **no `get_budget`** and no way to send
  a custom request, so the budget feature would be our own hand-assembled
  message handling on top of it. More of our code in the sensitive path, not
  less.
- Against: it is a compiled Rust blob with its own networking, outside
  Android's. Harder to inspect, and the "one library" is hundreds of Rust
  packages inside.

**Why Quartz:** for a tool whose whole job is to ask wallets read-only questions
and interpret the answers precisely, the library that already speaks that
dialect is the safer choice, and its long tail is a cost that can be measured
and listed. Whichever is chosen, our own tests run the published NIP-44 test
vectors against it, and it sits behind a wrapper (below) so it can be swapped.

One thing we write ourselves either way: **the connection string parser.**
Quartz's keeps only the first relay when a string lists several. Parsing is not
cryptography, and the brief already asks for it to be tested against hostile
input.

## Encryption at rest: a change from the brief

The brief suggested Google's Tink library "or the current recommended
equivalent". Google deprecated its own wrapper library (Jetpack Security) in
mid-2025 with the instruction to use "existing platform APIs and direct use of
Android Keystore". The plan follows that: **the phone's built-in Keystore
directly, no extra library.**

- Each connection string is encrypted by a key that exists only inside the
  phone's secure hardware (the stronger "StrongBox" chip where the phone has
  one).
- The hardware will only use the key shortly after you authenticate with
  fingerprint, face or PIN. So the lock is enforced by the hardware, not just by
  a screen the app draws.
- The hardware chooses the random values encryption needs, so there is nothing
  cryptographic for our code to get wrong.
- The whole original connection string is stored encrypted and handed back
  byte-for-byte on copy. Names, purposes, relays, the wallet's public key and
  "used in" lists are stored unencrypted in the app's private storage.

Consequences, also in the threat model: a new phone starts empty, and removing
your screen lock entirely destroys the vault.

Secure hardware behaves a little differently between phone makers, so
Milestone 1 starts by proving this works on the emulator and on your phone
before anything is built on top of it.

## How the app is put together

Five parts, each with one job:

| Part | Job |
|---|---|
| Screens | What you see: lock, list, detail, add, rotate |
| Vault | Encrypts, stores and decrypts connections. The only part that touches the hardware key |
| Lock | Decides when the app is locked, and asks for fingerprint or PIN |
| Wallet queries | Asks wallets the read-only questions and turns replies into a health status |
| Clipboard | Copies a secret out, flags it sensitive, clears it later |

Three chokepoints, each backed by an automated check that fails the build if
bypassed:

1. **One door to the wallet.** The rest of the app can ask only four things:
   read the info event, `get_info`, `get_balance`, `get_budget`. Payment and
   invoice calls exist in the library but are unreachable from the app, and the
   check scans for them by name.
2. **One door to the network.** Connections are opened in one place, only to
   relays taken from a stored connection string.
3. **One door to the log.** Nothing else may write a log line, and that one
   place refuses secrets and connection strings.

## Building, testing and getting it onto your phone

- **Builds happen on this box**, with the tools described below.
- **Automated tests** run on this box: the parser against malformed and hostile
  strings, the health-status logic, and the three build checks above.
- **Real wallets are never used from this box.** Wallet tests here run against
  a fake wallet and a fake relay that exist only inside the test suite. **Do not
  paste a real connection string into a chat with me, ever.** Real connections
  only go into the app on your phone.
- **The emulator** is a pretend Android phone running on this box with no
  screen. The encryption tests the brief asks for can only run on Android,
  because the secure key store does not exist anywhere else, and the emulator
  lets them run unattended. It also lets me see the screens I build.
- **The Pixel 6a, plugged into this box,** is where builds get installed and
  where the real secure chip and fingerprint sensor get exercised. It runs in
  developer mode for this. **While it does, keep only small-budget connections
  on it.** The Pixel 8 gets the app the normal way at Milestone 5 and never
  needs developer mode.
- **You test on the Pixel 6a** after each milestone, with written steps.

## Milestones

Each ends with a plain-English summary, the test results, anything that differed
from this plan, and install steps. No milestone starts without your go-ahead.

| | What gets built | What you can do at the end |
|---|---|---|
| **M1** | Build tools, the app skeleton, the lock, the encrypted vault, add by pasting, list, delete | Install it, unlock with fingerprint, paste a connection, see it listed, delete it |
| **M2** | The app's own PIN with fingerprint shortcut, the paste-into field, the detail screen, names and labels, "used in" lists, reveal and copy with all the clipboard and screen protections, the real icon | Set a PIN, organise connections, copy one out and paste it into another app |
| | *Leak review #1, written up in `SECURITY_REVIEW.md`* | |
| **M3** | Wallet queries and health checks, one at a time and "check all" | See supported methods, balance, budget where the wallet offers it, and an honest status for each connection |
| **M4** | Rotation helper | Swap in a new secret, work through the re-paste checklist, confirm the old one is dead |
| **M5** | Hardening, leak review #2, README, first public release | Use it for real |

## The build setup on this box

Installed 2026-10-05, all under Reed's home directory, nothing system-wide:
Java 21 in `~/.jdks`, the Android tools in `~/android-sdk` (about 6 GB), and one
virtual Pixel 6 running Android 16. The emulator was started without a screen,
booted in under a minute, and shut down again. It reports a hardware key store
and a fingerprint sensor, which the tests can trigger on command.

Reed added himself to the `kvm` group for this (`sudo usermod -aG kvm reed`;
undo with `sudo gpasswd -d reed kvm`).

Google's installer accepted the Android SDK license on Reed's behalf as part of
installing. Its usage analytics are off.

## Things this plan cannot promise yet

- **How each wallet behaves after a revoke.** The spec says a dead secret gets
  an "unauthorized" reply. Whether your wallets send that or just go silent
  decides how often you see "Revoked" versus "No answer". Measured at M3.
- **Whether the clipboard reliably clears while the app is in the background.**
  Android permits it in principle. Tested on your phone at M2.
- **`get_budget` coverage.** It is in no finished spec; it is a proposal opened
  2026-09-11. Wallets that support it do so by convention.
- **Exact tool versions.** The versions in `DEPENDENCIES.md` are what was
  current on 2026-10-04 and get confirmed when the first build runs.

## Milestone 1: what was built, and where it differs from this plan

Built: the app skeleton, the lock, the encrypted vault, add by pasting, the
list, delete. 62 automated tests run on the build box and 2 more run against the
real Android key store on the emulator; all pass. The release build was run on
the emulator end to end.

Differences from the plan above, all in the direction of less:

- **The unlock prompt is Android's own, with no library.** The plan listed
  Google's Biometric library; with Android 11 as the minimum it adds nothing.
- **No DataStore library.** The inventory is one file, written to a temporary
  file and swapped in.
- **The app has no network permission at all yet.** It arrives with Milestone 3.
  A build check fails if it appears earlier.
- **The hardware window is 30 seconds.** The secure hardware uses the key only
  within 30 seconds of a fingerprint, face or PIN check. In practice: unlocking
  the app, then taking longer than that to paste and name a connection, means
  one more fingerprint touch when saving.
- **Locking happens the instant the app leaves the screen.** The first version
  waited for Android's "stopped" signal, which on the emulator arrived a second
  or two late, long enough to switch away and back and find the app still open.
  Testing caught it; it now locks on the earlier "paused" signal.
- **Debug builds allow screenshots; release builds never do.** That is how the
  screens get checked during development. Only release builds go on a phone.
- **Built against Android 17 libraries, behaving as an Android 16 app.** The
  newest Compose requires the former; the latter matches what the emulator
  runs. The Pixel 6a turned out to run Android 16 too (security patch April
  2026, checked 2026-10-08), so this needs no change.

Found and fixed by the automatic code checker before it could matter: on
Android 11 and 12 the code that recognises "the hardware wants a fresh unlock"
referred to something that only exists from Android 13.

## Milestone 2: the plan

Proposed 2026-10-09. Nothing below is built until Reed says go.

### 1. The app's own PIN (decision 9)

**What you will see.** The first time the app opens it asks you to choose a
PIN of six or more digits and type it again. If the phone has a fingerprint
enrolled, a switch offers "Unlock with fingerprint too", on by default. From
then on the lock screen is a number pad with a fingerprint button. Unlocking,
and the re-check before revealing or copying a secret, take either one. The
phone's own PIN or pattern is never accepted as a substitute. Auto-lock stays
as it is: sixty seconds idle, and the instant the app leaves the screen.

**What changes underneath.** Today each connection is encrypted straight under
a hardware key that only works for thirty seconds after a phone unlock. In
Milestone 2 the connections are encrypted under a random vault key, and that
vault key is kept in two locked copies:

- Copy A is locked by a key stretched from your PIN (PBKDF2 with HMAC-SHA256,
  built into Android, a random salt and a high iteration count), and then
  locked again by a hardware key that needs no fingerprint. The second lock
  means the app's files are useless off the phone even to someone who knows
  the PIN.
- Copy B exists only while the fingerprint switch is on. It is locked by a
  hardware key that the chip will use only right after a fingerprint, with no
  fallback to the phone's PIN. Enrolling a new fingerprint makes Android
  destroy that key, which is correct: the app then asks for the PIN once and
  quietly makes a fresh copy B.

Changing the PIN re-locks copy A only. Turning the fingerprint switch off
deletes copy B and its hardware key. The thirty-second window disappears for
the PIN path, so a slow paste-and-name no longer costs a second prompt; it
remains for the fingerprint path, where it is invisible in practice.

**Wrong PIN.** Five misses are free. After that each miss doubles a wait,
starting at thirty seconds and capped at an hour, and the wait survives
closing the app. No wipe-after-N-tries, because that only punishes the owner.

**Forgotten PIN.** There is no recovery and the lock screen says so. A "Start
over" button, behind a confirmation, wipes the vault. The right follow-up is a
revoke at each wallet, and the screen says that too.

**What this does and does not defend against.** It stops someone who knows
your phone PIN from walking into this app. It does not stop someone who has
rooted your unlocked phone: with the hardware key available to them, a
six-digit PIN can be guessed by machine in hours. That is the same position
Phoenix and Zeus are in, and it is bounded by the wallet-side budgets. The
threat model gets a paragraph saying this.

**Existing data.** The Milestone 1 build on the Pixel 6a holds test entries
only. The Milestone 2 build recognises the old file format and offers to
start fresh rather than carrying a migration that nobody needs.

### 2. The paste-into field (decision 10)

The add screen gets a text field above the "Paste from clipboard" button. It
is a password-style field: the keyboard is told not to learn from it, not to
suggest for it, and not to show it in clipboard previews. Both routes feed the
same parser and the same error messages. Nothing typed or pasted there is
logged, and the existing build check keeps it that way.

### 3. The detail screen, labels and "used in"

Tapping a connection opens it. The screen shows the name, purpose, wallet
label, relays, the wallet's public key, the Lightning address if there was
one, the date added, and the "used in" list. Name, purpose and wallet label
are editable in place. "Used in" is a list of short labels you add and remove
one at a time ("Amethyst", "Fountain"); it is the memory the rotation helper
in Milestone 4 will turn into a checklist. The secret is shown as "present,
hidden" and nothing more. Delete moves here from the list.

### 4. Reveal and copy

Two buttons on the detail screen, each behind a fresh fingerprint-or-PIN
check every time, with no grace period:

- **Reveal** shows the full connection string for thirty seconds, then hides
  it again. The screen already refuses screenshots and recent-apps previews in
  release builds.
- **Copy** puts the string on the clipboard flagged sensitive, so Android 13
  and later hide it from the clipboard preview, and clears it sixty seconds
  later. Whether Android lets the app clear the clipboard while another app is
  in front is the thing the plan could not promise; it gets tested on the
  Pixel 6a and the result is written down here either way.

### 5. The icon

Idea 18 from the logo board, built from Reed's NWC.svg (edited 2026-10-08):
the orange card with the plug cut out and the purple corner, inside a white
shield, on #121212. It replaces the key-ring icon, with a single-colour
version for Android's themed icons.

### 6. Checks and the first leak review

- Unit tests: the two-copy vault key round-trips with a fake PIN and a fake
  chip; a wrong PIN opens nothing; the wait schedule; edits and "used in"
  changes survive a reload; the old file format is detected.
- On the emulator: the real chip with a real PIN and a real enrolled
  fingerprint, including "new fingerprint enrolled" invalidating copy B.
- The build-time rules keep their teeth: still no network permission, still
  one place that logs, still no payment method names, plus a new rule that
  the PIN never reaches the log.
- **Leak review #1** goes into `SECURITY_REVIEW.md` at the end of the
  milestone: clipboard, screenshots, the keyboard, crash output, backups,
  exported components, and the PIN handling.

### Defaults in force unless Reed objects

Six to sixteen digits, numbers only. Reveal lasts thirty seconds. Clipboard
clears after sixty. Re-check before every reveal or copy. Fingerprint
shortcut on by default where a fingerprint exists.

### What stays out

No wallet queries or health checks yet; those are Milestone 3, with the
network permission. No PIN change screen yet unless it falls out cheaply; the
workaround is "Start over". No export.

## Where this plan departs from the brief

- `get_budget` is treated as optional and wallet-specific, because NIP-47 was
  cut down to a five-command core on 2026-08-01 and `get_budget` is not in it.
- A failed check is not treated as proof of revocation. A fifth status, "No
  answer", is added.
- Quartz instead of the Rust bindings.
- Android Keystore directly instead of Tink.
- Screenshot blocking on every screen instead of only the screens that show
  secrets.
- The whole connection string is stored encrypted, not only the secret, so that
  what you copy out is exactly what the wallet gave you.
