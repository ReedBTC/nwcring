# NWC Ring — Threat Model

Approved by Reed as part of Milestone 0, 2026-10-05. This says what the app
protects, from whom, what it cannot protect against, and what is left over in
each case. It is written to be read without the code. Parts that describe
features not built yet (copying out, health checks) say what the app will do.

## The short version

- NWC Ring keeps your Nostr Wallet Connect connection strings in one place on
  your phone, encrypted under a vault key that is itself locked twice: by a PIN
  that belongs to NWC Ring (not the phone's PIN) and by the phone's secure
  hardware. A fingerprint can stand in for the PIN if you switch that on.
- Putting every connection in one place makes that place worth attacking. That
  is the central tradeoff of the app and it is accepted on purpose.
- **The most anyone can take with a stolen connection is what the wallet lets
  that connection spend.** That ceiling is the budget you set in the wallet, not
  anything this app controls. A connection with no budget has no ceiling short
  of the wallet's balance. The app will show when it can't see a budget, and the
  README will say this plainly.
- The app itself never spends, never creates invoices, and talks to nothing
  except the relays written inside your own connection strings.

## What is being protected

| Thing | Why it matters |
|---|---|
| The connection strings (specifically the `secret` inside each) | Whoever holds one can do whatever the wallet allows that connection to do: spend up to its budget, and often read the balance and payment history. |
| Your inventory: names, purposes, wallet labels, "used in" lists | Not money, but it is a map of which wallets you have and which apps you use them in. |
| What the wallets report back: balances, budgets | Private financial information. |

## What the app does not try to defend against

Saying these out loud matters more than the list of protections.

- **A phone that is already compromised**: rooted, running malware with system
  privileges, or a malicious keyboard. Anything you can see or paste, it can too.
- **Someone who knows your NWC Ring PIN and has your phone.** They are you, as
  far as the app can tell. (Knowing only the phone's PIN is not enough: that was
  the point of giving the app its own, decided 2026-10-08.)
- **The apps you paste a connection into.** Once a secret is pasted into another
  app, that app's security is what protects that copy.
- **The wallet itself.** It holds the money and issues the secrets.
- **Being forced to unlock.**

## Scenarios

### The phone is lost or stolen while locked

The secrets are encrypted on disk under a vault key that is stored only in two
locked copies. One is locked by your NWC Ring PIN (stretched with PBKDF2 so
each guess costs about half a second) and then by a key inside the phone's
secure hardware. The other, if you turned fingerprint unlock on, is locked by a
hardware key that only works for a few seconds after a fingerprint. Pulling the
storage out of the phone gets an attacker encrypted data and no key. Guessing
the PIN on the phone is slowed down: five tries are free, then each miss doubles
a wait that starts at thirty seconds and caps at an hour.

*Left over:* someone who gets past the phone's own lock screen can try PINs,
slowly. Someone who roots the unlocked phone has the hardware key available and
can guess a six-digit PIN by machine in hours, because the stretching is the
only thing in their way at that point. That is the same position Phoenix and
Zeus are in, and it is bounded by the wallet-side budgets. A longer PIN helps:
each extra digit multiplies the work by ten. The wait after wrong PINs is
enforced by the app, not the hardware, so moving the phone's clock forward after
force-stopping the app can shorten it; the stretching cannot be skipped.

*What you should do:* revoke every connection in the wallets and issue new ones.
Connection secrets are disposable, and that is the right response whether or not
anyone got in. Note the catch: **the list of what to revoke was on the lost
phone.** This is the best argument for a backup of the *inventory* (names and
wallets, no secrets); see the open decisions in `PLAN.md`.

### The phone is taken while unlocked and the app is open

The app locks itself after 60 seconds without a touch and the moment it leaves
the screen, and locking wipes the vault key from memory. Seeing or copying a
secret asks for your fingerprint or NWC Ring PIN again, every time, even when
the app is already open, and the check is real: it re-opens a locked copy of the
vault key rather than just flipping a flag.

*Left over:* the names, purposes and "used in" lists are visible while the app
is open. The secrets are not.

### A malicious or nosy relay

Every health check goes through the relay named in the connection string. What
a relay can and cannot do:

- **Cannot read** requests or replies. They are encrypted between the app and
  the wallet.
- **Cannot forge** a reply. Replies are signed by the wallet, and the app checks
  the signature, that it came from the wallet named in the connection string,
  and that it answers the exact request the app sent.
- **Can see** your phone's IP address, when you check, and the two public keys
  involved. If several of your connections use the same relay and you tap "check
  all", that relay can tell those connections belong to one person.
- **Can drop or delay** messages, which makes a working connection look
  unreachable. It cannot make a working connection look revoked, because
  "revoked" is only ever shown when the wallet itself signs a reply saying so.

*Left over:* the linking of connections by IP and timing. A VPN moves that
knowledge from the relay to the VPN provider; the app does not solve it.

### Someone watching the network

Connections to relays are encrypted (TLS), and the contents are encrypted again
inside that. An observer on the Wi-Fi sees that the phone talked to a relay, not
what was said. The app will refuse relay addresses that are not encrypted
(`ws://` rather than `wss://`).

### Another app reads the clipboard

Copying a secret out is the one moment it leaves the vault in the clear, and it
is unavoidable: pasting it into another app is the whole point.

- Since Android 10, an app can only read the clipboard while it is the app on
  screen (or the keyboard). Background apps cannot.
- The app marks a copied secret as sensitive, which stops Android 13 and later
  from showing it in the on-screen clipboard preview.
- The app clears the clipboard after a short delay.
- When you paste a connection *into* NWC Ring, it offers to clear the clipboard
  then too.

*Left over, and it is real:* the app you paste into reads the secret, which is
intended. **Some keyboards keep their own clipboard history, and no app can
clear that.** If your keyboard has a clipboard history feature, a copied secret
may sit in it. Clearing after a delay depends on Android letting the app act a
minute later while it is in the background. **Measured on the Android 16
emulator on 2026-10-10: it works.** The string was pasteable in another app
right after the copy and gone after the clear, with NWC Ring in the background
throughout. Android can pause a backgrounded app's timers, so as a backstop the
app also clears the clipboard the next time it comes to the screen if the
deadline has passed. The clear is unconditional: anything you copied in the
meantime goes too.

The revealed string on the detail screen is deliberately not selectable, so the
only way to copy it is the Copy button with the protections above. Pasting a
connection *into* NWC Ring goes through a password-style field, which tells the
keyboard not to learn from it or suggest it; what the keyboard does with its own
clipboard history is, again, the keyboard's business.

### Screenshots, screen recording, the recent-apps view, casting

The whole app is flagged so Android will not include it in screenshots, screen
recordings, the recent-apps thumbnails or a cast screen. This is applied to
every screen, not just the ones that show secrets, so there is no screen to
forget.

*Left over:* someone looking over your shoulder, or a camera pointed at the
phone. Secrets are hidden until you tap to reveal and authenticate.

### Backups and moving to a new phone

The app's data is excluded from Android cloud backup and from device-to-device
transfer. The hardware key could not be moved anyway, so a transferred copy
would be unreadable.

*Consequence you should expect:* **a new phone starts empty.** So does a factory
reset. And because the key is tied to your screen lock, **removing your screen
lock entirely (setting it to "none" or "swipe") destroys the key and with it
the vault.** Changing your PIN, or adding a fingerprint, does not. This is
deliberate: the alternative is a key that survives without a lock screen.

### A bug in the app

The plausible bugs, and what stands in front of each:

- *A secret written to a log or a crash report.* The app has one place where
  anything gets logged, it refuses to log secrets or connection strings, and an
  automated check fails the build if any other code tries to log directly. There
  is no crash-reporting service; nothing is sent anywhere.
- *The lock screen has a hole.* The lock is not only a screen the app draws. The
  secure hardware itself refuses to use the key unless you authenticated in the
  last 30 seconds, so a bug in the app's screens does not by itself hand over
  the secrets. One honest limit: unlocking the *phone* also counts as
  authenticating, so for 30 seconds after the phone is unlocked the hardware
  gate is open and the app's own lock is the only barrier.
- *The app spends money by mistake.* The code that talks to wallets is wrapped
  so that only four read-only questions can be asked at all, and an automated
  check fails the build if any payment-related call appears anywhere in the app.
- *The app is tricked by a hostile connection string.* Parsing is tested against
  malformed and deliberately hostile input.

*Left over:* bugs nobody has thought of. That is what the adversarial reviews
after Milestone 2 and at Milestone 5 are for, and why the worst case is bounded
by the wallet budget rather than by this app being perfect.

### A tampered app or a tampered update

Android will only install an update over the existing app if it is signed with
the same signing key as the original. That key is therefore the thing that
proves an update is really NWC Ring.

- If the signing key is **stolen**, someone could build a malicious "update".
  You would still have to be persuaded to install it.
- If the signing key is **lost**, no update can ever be installed over the
  existing app; everyone has to uninstall, losing their vault, and start again.

The key is never in the repository. Where it lives and who backs it up is a
decision in `PLAN.md`.

### A dependency turns malicious

The app is built from other people's libraries, and one of them could ship a
bad version. Versions are pinned (an update never arrives on its own),
`DEPENDENCIES.md` lists every one and why it is there, and the app asks for
almost nothing: permission to show the fingerprint prompt and, from Milestone 3,
internet access. There is little else for bad code to reach.

*Left over:* a pinned version that was already bad when it was chosen. Keeping
the list short is the main defence, and the library choice in `PLAN.md` is a
real tradeoff on exactly this point.

### Health checks have a side effect in the wallet

Not a security risk, but worth knowing: a health check is a real request to the
wallet. A wallet that shows "last used" for each connection will show the time
of your last check, not the last time one of your apps used it.

## What a health check can honestly tell you

| What the app sees | What it means | Shown as |
|---|---|---|
| The wallet answers the question | The secret works | Working |
| The wallet answers "not permitted" or "not supported" | The secret is alive; it just isn't allowed that question | Working (limited) |
| The wallet answers "this key has no wallet connected" or "expired" | The wallet itself says the secret is dead | Revoked |
| The relay is reachable but the wallet says nothing | **Either** revoked **or** the wallet is offline. These look identical from outside | No answer |
| The relay cannot be reached | Says nothing about the secret | Relay unreachable |

The app never turns silence into "revoked". After a rotation, if the new secret
gets an answer and the old one gets silence in the same minute, the app can say
the wallet is clearly online and ignoring the old secret, and it will label that
as a strong sign rather than proof.
