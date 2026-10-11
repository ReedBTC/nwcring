# NWC Ring — Security Review

A record of the dedicated leak-hunting passes: reviews whose only question is
"how could a secret get out?". Written to be read without the code.

## Review 1: after Milestone 2, 2026-10-11

Scope: everything that exists at the end of Milestone 2. The app has no network
access yet, so relays and the wire are not in this review; they come at
Milestone 3 and the final review at Milestone 5.

### Where a secret exists, and what guards each place

| Place | Guard |
|---|---|
| On disk, `vault.json` | Each connection string encrypted (AES-256-GCM) under the vault key. Names, relays, wallet keys and labels are in the clear, by design. The file lives in a folder Android never backs up. |
| On disk, `keyring.json` | The vault key in two locked copies: under the stretched PIN then the phone's device key; and under the fingerprint key while that is on. Never the PIN, never the key in the clear. Verified by a test that reads the file. |
| In memory, unlocked | The vault key, wiped to zeros on lock, on leaving the screen, and after 60 seconds idle. |
| The PIN | Typed on the app's own pad, never a keyboard; held as characters and wiped after each check; never stored, logged or compared as text. |
| A pasted connection | In the password-style field until "Use" is tapped, then parsed and held in memory until saved or the screen closes. |
| A revealed connection | On screen for 30 seconds, not selectable, gone on lock. |
| The clipboard | Flagged sensitive; cleared after 60 seconds, or on the app's next return if that was late. |
| The log | One place writes it, and that place blocks anything that looks like a secret, a connection string or a long hex value. A build check fails if any other file logs. |

### Checked, and clean

- **Logs.** The build check (`SourceRulesTest`) fails if any file but `SafeLog.kt`
  logs, and if any log call is handed a value named like a PIN, key, secret or
  plaintext. The log lines produced during a full walk-through on the emulator
  were read: outcomes and counts only.
- **Crash output.** No exception in the app carries data: parser errors are an
  enum, cipher errors are typed and messageless, and `SecretText` prints as
  `[secret]`.
- **Screenshots, recordings, recent apps, casting.** The whole window is flagged
  secure in release builds, including the PIN dialog. The fingerprint prompt is
  the phone's own window.
- **Backups and device transfer.** Off in the manifest, with explicit exclusion
  rules, and the files sit in the no-backup folder. A build check enforces all
  three.
- **Exported components, links, intents.** Only the launcher activity is
  reachable from outside, and nothing accepts a link or a shared text. Enforced.
- **Notifications.** None.
- **Keyboard.** The connection string field is a password field (no learning,
  no suggestions, no preview); the PIN never touches a keyboard. Enforced.
- **Clipboard.** Only one file writes to it, with the sensitive flag and the
  timed clear; enforced. Clearing from the background was measured on the
  Android 16 emulator and works.
- **Debug builds.** They allow screenshots and `run-as`, so they install under a
  different name and never go on Reed's phone.
- **The old Milestone 1 key.** Starting over deletes it along with the new ones.

### Found and fixed in this review

1. **The revealed connection string could be copied by long-press.** It sat in a
   selectable text block, so Android's own Copy would have put it on the
   clipboard with no sensitive flag and no timed clear. Fixed: not selectable.
   A build check now fails if the revealed text is ever put back inside a
   selection container.

### Known and accepted, written down so they are not forgotten

- **A rooted, unlocked phone defeats the PIN in hours.** With the device key
  available, the only cost per guess is the stretching (about 0.4 s), so a
  six-digit PIN falls to a machine in hours. Longer PINs help tenfold per digit.
  Same position as Phoenix and Zeus; bounded by wallet budgets. In the threat
  model.
- **The wrong-PIN wait is enforced by the app, not the hardware.** Force-stop
  the app and move the clock forward and the wait is shorter. The stretching
  itself cannot be skipped.
- **The pasted string is a Java string until the screen closes.** The field
  needs one; it cannot be wiped the way the PIN digits are. It lives only while
  the Add screen is open.
- **The clipboard clear is unconditional.** Anything copied in the 60 seconds
  after a NWC Ring copy goes with it.
- **Accessibility services and keyboards with clipboard history** can see what
  is on screen or pasted. Outside the app's control; in the threat model.
- **The emulator reports its key store as software.** Expected on an emulator;
  the Pixel 6a reports what it really has on the Settings screen.

### Not yet reviewed

Everything that arrives with the network at Milestone 3: what goes to a relay,
what comes back, and what the one-door rule has to cover.
