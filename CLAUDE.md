# NWC Ring — Claude Code Notes

An Android app that stores, organizes and health-checks **Nostr Wallet Connect
(NIP-47) connection strings** from any wallet, in one encrypted place on the
phone. It is an inventory and health tool, not a wallet.

**Status: Milestone 0 drafted 2026-10-04, awaiting Reed's approval. There is no
code yet.** Nothing gets built until he has approved `THREAT_MODEL.md`,
`PLAN.md` and `DEPENDENCIES.md`; the decisions he owes are the table at the top
of `PLAN.md`.

**No real connection string ever goes into a chat, a test, a fixture or this
box.** Wallet tests here run against a fake wallet and fake relay; real
connections exist only in the app on Reed's phone.

The idea the app exists for: one connection per *purpose* ("Nostr zaps",
"Podcasting"), pasted into every app that serves that purpose, each with its own
wallet-side budget. That leaves two problems, and they are the product:
**inventory** (which secrets exist, with what permissions and budgets) and
**rotation** (revoking a shared secret means re-pasting a new one everywhere it
was used, and remembering where that was).

## Working with Reed

- **Reed owns the project and does not review code.** He knows Bitcoin,
  Lightning and Nostr well at the user and protocol level. Every explanation,
  decision record and test result must stand on its own in plain English.
  Explain the tradeoff, not the protocol basics.
- **Plan first, per milestone.** Propose the plan in plain English and wait for
  his approval before writing that milestone's code.
- **He discounts praise and trusts pushback.** If a plan, a requirement or this
  file is wrong or out of date, say so directly.
- **Commit locally freely; ask before every `git push`.**
- **End a turn by naming whose move it is.** Commands for him are one per block,
  on a single line, with what each should print.
- **Open decisions are his.** Raise them when they become relevant, never decide
  silently: backup/recovery (none, passphrase-protected export, or a recovery
  phrase), minimum Android version, distribution channel, the package name
  (`com.reedbtc.nwcring` is the working assumption), the license, and the
  later-phase features.

## Hard lines

If one of these turns out to be infeasible, stop and explain before working
around it.

- **The app never calls a method that moves or requests money**: `pay_invoice`,
  `pay_keysend`, `make_invoice`, `pay`, `receive`, hold invoices, offers. Read
  only: the info event, `get_info`, `get_balance`, and `get_budget` where a
  wallet offers it.
- **No hand-rolled cryptography.** Established, maintained libraries for
  secp256k1, Schnorr, NIP-44 and NIP-04.
- **Secrets at rest** are encrypted under a key held in Android Keystore
  (hardware-backed where available). Non-secret metadata may live in normal app
  storage.
- **Unlock** by biometric or device credential to open the app, and again before
  revealing or copying a secret. Auto-lock after a short timeout and on
  backgrounding.
- **Secrets are hidden by default**, shown only after an explicit tap plus
  re-authentication. `FLAG_SECURE` on every screen that can show one.
- **Clipboard**: copied secrets are flagged sensitive (Android 13+) and cleared
  after a short delay.
- **Backups**: app data is excluded from cloud backup and device-to-device
  transfer.
- **Logging**: never a secret, a full connection string or a decrypted payload.
  A test or lint check enforces it.
- **Network**: websocket connections to the relays named in the user's own
  connection strings, and nothing else. No accounts, sync, servers, analytics,
  crash reporting or ads.
- **Dependencies**: few, pinned, and every one listed in `DEPENDENCIES.md` with
  its version and the reason it is there.

## Scope

In the MVP: app lock; add by pasting a connection string (parse, validate,
reject malformed input with a clear message); per-connection name, purpose,
wallet label, relays, wallet pubkey, encrypted secret, "used in" labels, date
added, last health result; list and detail screens; wallet queries; health check
per connection and "check all"; copy-out with the protections above; the
rotation helper; delete.

Not in the MVP unless Reed asks: QR scanning, wallet auto-identification, deep
links to wallet pages, backup/export.

Milestones: **M0** the three planning docs → **M1** skeleton, app lock,
encrypted storage, add/list/delete with parsing → **M2** detail screen, labels,
"used in", copy-out protections → **M3** NWC queries and health checks → **M4**
rotation helper → **M5** hardening. After each one Reed gets a plain-English
summary (what works, what changed, what deviated from the plan) and step-by-step
install instructions that assume he has never sideloaded an APK. A leak-hunting
review goes into `SECURITY_REVIEW.md` at M5, and ideally after M2.

## Protocol facts, checked against the specs on 2026-10-04

Re-check before M3; the spec moved twice in 2026.

- **NIP-47 was cut down to a core on 2026-08-01** (nips #2419). The core is
  three event kinds (info 13194, request 23194, response 23195) and five
  commands: `pay_invoice`, `make_invoice`, `lookup_invoice`, `get_balance`,
  `get_info`. Everything else is a numbered extension in
  `github.com/nostr-wallet-connect/nwc` (02 notifications, 03 hold invoices,
  04 keysend, 05 transaction history, 06 metadata, 07 deep links, 08
  client-initiated connections, 09 payment lookup, 12 BOLT12, 321 BIP-321).
- **`get_budget` is in no merged spec.** It is an open proposal there (PR #7,
  "NWC-13", opened 2026-09-11). Wallets that answer it do so by convention.
  "This wallet doesn't report a budget" is a normal state in the UI, not an
  error.
- **Encryption** is negotiated from the info event's `encryption` tag
  (`nip44_v2 nip04`, space-separated). No tag means NIP-04 only. The request
  carries its own `encryption` tag. Prefer NIP-44.
- **The URI**: `nostr+walletconnect://<wallet pubkey>?relay=…&secret=<64 hex>`;
  `relay` may repeat and is percent-encoded; `lud16` is optional.
- **What a health check can and cannot prove.** Any signed response from the
  wallet, an error included, proves the secret is still recognised:
  `RESTRICTED` means alive but not permitted to do that. `UNAUTHORIZED` ("this
  public key has no wallet connected") is the spec's answer for a dead secret.
  **Silence proves nothing**: a revoked secret and an offline wallet can look
  the same from the outside. Never report silence as "revoked".
- **No client-side revoke exists**, and nothing in the protocol says which apps
  hold a secret. Revocation happens in the wallet; "used in" is a list the user
  keeps.
