# NWC Ring — Plan

**Draft for Reed's review, 2026-10-04. Nothing is built yet.** Versions and
library facts below were looked up on that date.

## Decisions needed before Milestone 1

Each has a recommendation. The first is the one with a real tradeoff.

| # | Decision | Recommendation |
|---|---|---|
| 1 | Which Nostr library | **Quartz**, pinned to one release. See "The Nostr library" below |
| 2 | Oldest Android version supported | **Android 11** (2020). From there Android handles "fingerprint or PIN" for hardware keys one consistent way; older versions need special-case code in exactly the part that should be simplest. Need to know what your own phone runs |
| 3 | Package name | `com.reedbtc.nwcring`. Permanent once anyone installs the app |
| 4 | License | **MIT**, same as your other two repos |
| 5 | Backup | **No backup of secrets.** They are disposable; after a lost phone the right move is revoke and reissue. Worth adding later: an export of the *inventory only* (names, wallets, "used in", no secrets), because after losing the phone that list is what you need in order to know what to revoke |
| 6 | How test builds reach your phone | **A download link on your home network** for Milestones 1 to 4, **GitHub Releases** from Milestone 5. Keeps unhardened builds of a secrets app off the public internet |
| 7 | The signing key | Created on this box at Milestone 1, stored outside the repo. **You keep an offline copy and its password.** Lose it and no update can ever install over the existing app |
| 8 | An Android emulator on this box | **Yes.** It needs one command from you (see "What I need from you") |

Defaults I will use unless you object: auto-lock after 60 seconds idle and
immediately when the app leaves the screen; clipboard cleared 60 seconds after
a copy; relay addresses that are not encrypted (`ws://`) are refused.

One question, not a decision: **which wallets do you have NWC connections
from?** That becomes the test list for Milestone 3.

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

- **Builds happen on this box.** I install Java and the Android build tools
  under your home directory at Milestone 1 (about 6 to 8 GB, no sudo, nothing
  system-wide, nothing near your bots).
- **Automated tests** run on this box: the parser against malformed and hostile
  strings, the health-status logic, and the three build checks above.
- **Real wallets are never used from this box.** Wallet tests here run against
  a fake wallet and a fake relay that exist only inside the test suite. **Do not
  paste a real connection string into a chat with me, ever.** Real connections
  only go into the app on your phone.
- **The emulator** is a pretend Android phone running on this box with no
  screen. It matters for two reasons. The encryption tests the brief asks for
  can only run on Android, because the secure key store does not exist anywhere
  else. And it lets me see the screens I build instead of building blind. It
  cannot stand in for a real fingerprint sensor or a real secure chip, so your
  phone remains the final test.
- **You test on your phone** after each milestone, with written steps. Until
  Milestone 5 is done, use a throwaway connection with a tiny budget.

## Milestones

Each ends with a plain-English summary, the test results, anything that differed
from this plan, and install steps. No milestone starts without your go-ahead.

| | What gets built | What you can do at the end |
|---|---|---|
| **M1** | Build tools, the app skeleton, the lock, the encrypted vault, add by pasting, list, delete | Install it, unlock with fingerprint, paste a connection, see it listed, delete it |
| **M2** | Detail screen, names and labels, "used in" lists, reveal and copy with all the clipboard and screen protections | Organise connections, copy one out and paste it into another app |
| | *Leak review #1, written up in `SECURITY_REVIEW.md`* | |
| **M3** | Wallet queries and health checks, one at a time and "check all" | See supported methods, balance, budget where the wallet offers it, and an honest status for each connection |
| **M4** | Rotation helper | Swap in a new secret, work through the re-paste checklist, confirm the old one is dead |
| **M5** | Hardening, leak review #2, README, first public release | Use it for real |

## What I need from you

Nothing for this milestone. For Milestone 1, one command so the emulator can
use this box's virtualisation. It adds your user to one group and changes
nothing else:

```
sudo usermod -aG kvm reed
```

It prints nothing. It takes effect the next time you log in to the box, so the
Claude session has to be restarted after it. To undo: `sudo gpasswd -d reed kvm`.

The emulator may also want a few system libraries this headless box lacks. I
will only know when I try it, and will give you the exact command if so.

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
