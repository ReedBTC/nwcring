# NWC Ring

An Android app that keeps your **Nostr Wallet Connect (NIP-47) connection
strings** from any wallet in one encrypted place on your phone, so you can see
what you have, check that each one still works, and rotate one without losing
track of where it was pasted.

It is an inventory and health tool. **It is not a wallet and it never spends.**

> **Status: early development (Milestone 2 of 5).** It can lock behind its own
> PIN or a fingerprint, store, organise, reveal, copy and delete connections. It
> cannot yet health-check them. It has had one leak review
> (`SECURITY_REVIEW.md`) but not its final one. Do not put a connection with a
> meaningful budget in it yet.

## Why it exists

Many people do not create one wallet connection per app. They create one per
*purpose* ("Nostr zaps", "Podcasting") and paste it into every app that serves
that purpose, each with its own budget in the wallet. That leaves two problems:

- **Inventory.** Secrets from several wallets, and no one place to see them.
- **Rotation.** Replacing a shared secret means re-pasting the new one into
  every app that had the old one, and remembering which apps those were.

## What it does and does not do

Does, today:

- Locks behind a PIN of its own, with fingerprint as an optional shortcut. The
  phone's PIN is never accepted. Locks again the moment it leaves the screen
  and after 60 seconds without a touch.
- Takes a connection string pasted into a password-style field or read from the
  clipboard, checks it, and stores it encrypted under a vault key that is itself
  locked by your PIN and by the phone's secure hardware.
- Shows each connection's wallet, relays, notes and a "used in" list of the apps
  you pasted it into.
- Reveals or copies a connection only after a fresh fingerprint or PIN check.
  A revealed one hides again after 30 seconds; a copied one is flagged sensitive
  and the clipboard is cleared after 60.

Will do (see `PLAN.md`): read-only wallet queries and health checks, a rotation
helper.

Never does:

- Pay, create invoices, or call any wallet method that moves or requests money.
- Use accounts, servers, cloud sync, analytics, crash reporting or ads.
- Talk to anything other than the relays written inside your own connection
  strings. (Today it has no network access at all.)
- Back anything up. A new phone starts empty.

## The risk, plainly

Putting every connection in one app makes that app worth attacking. The limit on
what a stolen connection can cost you is **the budget you set for it in your
wallet**, not anything this app does. A connection with no budget has no limit
short of the wallet's balance. Set budgets.

If you lose your phone: revoke your connections in your wallets and issue new
ones. The full reasoning, including what the app does not protect against, is in
[`THREAT_MODEL.md`](THREAT_MODEL.md).

## Checking a build is genuine

Release builds are signed with one key. Its certificate fingerprint (SHA-256) is:

```
E9:6C:DB:77:8A:D3:46:D6:0D:BC:68:CF:23:70:B8:EF:9C:78:E8:59:E2:5C:4F:04:82:AB:02:41:05:25:37:0D
```

`apksigner verify --print-certs <file>.apk` should print the same digest.

## Building it yourself

Needs JDK 21 and the Android SDK (platform 37).

```
./gradlew :app:testDebugUnitTest     # the tests that run anywhere
./gradlew :app:assembleDebug         # a debug build
./gradlew :app:assembleRelease       # unsigned unless you supply your own key
```

The encryption tests need a phone or emulator with a screen lock:
`./gradlew :app:connectedDebugAndroidTest`.

## Documents

- [`THREAT_MODEL.md`](THREAT_MODEL.md): what is protected, from whom, and what is left over.
- [`PLAN.md`](PLAN.md): decisions, design, and the milestones.
- [`DEPENDENCIES.md`](DEPENDENCIES.md): every library in the app and why.

## License

MIT. See [`LICENSE`](LICENSE).
