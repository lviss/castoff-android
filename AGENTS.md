# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Build/run/test commands and current scope are in [README.md](README.md) — don't duplicate them here.
- The FCast wire protocol (`app/src/main/java/org/castoff/control/fcast/`) is hand-ported from
  [lviss/castoff](https://github.com/lviss/castoff)'s `daemon/src/fcast.rs`. The two repos are
  intentionally separate with no shared code or generated bindings, so a protocol change on the
  daemon side (new opcode, changed field) has to be re-read and re-applied here manually — there is
  no automated sync or contract test between the repos.
- `FCastClient` opens a new short-lived TCP connection per command rather than holding one open
  across the app's lifecycle; see the class doc comment in `FCastClient.kt` for why. A separate
  `FCastStatusListener` (same package) holds one persistent connection open instead, because the
  daemon's unprompted `PlaybackUpdate` pushes can arrive at any time and would never be seen on a
  connection that's already closed by the time they land -- don't "fix" `FCastClient` into holding
  a connection open; add to `FCastStatusListener` instead. That status connection is read-only
  apart from its heartbeat `Ping` (see below).
- The status connection's three lifecycle invariants, each with the failure it prevents:
  (1) it is opened on composition with a saved host and **replaced on every return to the
  foreground** (`MainActivity`'s `ON_START` observer bumps a reconnect token) -- a socket Android
  killed while the app was backgrounded can sit half-open, so "still connected" can't be trusted
  on resume; (2) it sends a `Ping` heartbeat on a fixed cadence, because
  the daemon pushes **nothing at all** while idle/paused and nothing at connect time, so silence
  alone is *not* evidence of a dead link -- a plain read timeout would either flicker "not
  connected" on an idle daemon or never notice a real drop. The cadence is deliberately not gated
  on silence (a gate keyed on the last received frame is reset by the `Pong` it just triggered and
  then skips a beat), and `FCastStatusListener` requires the read timeout to exceed the interval;
  (3) the playback anchor is cleared
  whenever the link drops, so the local interpolation ticker cannot keep marching the progress bar
  forward from a stale anchor and claim live playback on a dead link.
- FCast v2 (and this daemon) has no connect-time "what is your current state?" request, so a
  client that connects while playback is paused or idle learns nothing until the next state
  change; only while playing does the daemon's ~1/s tick carry a fresh snapshot. The control screen
  therefore resets to "no playback reported yet" on each new connection rather than reusing the
  previous link's state, and never claims to be playing on unknown state.
- Device behaviour here is reproducible without hardware: an AVD named `test_avd` and an Android SDK
  live under `/home/ai/android-sdk-test`, whose `adb-wrapped` and `steam-run`-wrapped `emulator`
  are the NixOS-safe entry points (the raw SDK binaries won't run under the standard dynamic
  linker). `/home/ai/android-sdk-test/fake_daemon.py` only acks commands and never pushes, so for
  connection/push work you need a stand-in that mirrors the daemon's rules -- no push at connect,
  push on state change, ~1/s while playing. `FCastStatusListenerTest` is the committed encoding of
  that contract at the socket level; for UI-level checks, drive the screen with `adb shell input`
  and assert what it says with `uiautomator dump`.
- On NixOS (not GitHub Actions' Ubuntu runners), AAPT2's prebuilt binary won't run under the
  standard dynamic linker (`nix.dev/permalink/stub-ld`). Building locally under Nix needs an
  `-Pandroid.aapt2FromMavenOverride=<path to a wrapper named literally "aapt2">` pointing at a
  script that runs the real aapt2 through an FHS shim (e.g. `steam-run`); GitHub Actions CI needs
  none of this.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
