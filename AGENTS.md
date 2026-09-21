# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Build/run/test commands and current scope are in [README.md](README.md) — don't duplicate them here.
- The FCast wire protocol (`app/src/main/java/org/castoff/control/fcast/`) is hand-ported from
  [lviss/castoff](https://github.com/lviss/castoff)'s `daemon/src/fcast.rs`. The two repos are
  intentionally separate with no shared code or generated bindings, so a protocol change on the
  daemon side (new opcode, changed field) has to be re-read and re-applied here manually — there is
  no automated sync or contract test between the repos. A daemon PR's own description is not
  authoritative for opcode numbers: `SetImageWallpaper`/`ImageWallpaperUpdate` shipped as 20/21,
  not the 18/19 their originating PR description claimed, because 18/19 were already taken by an
  earlier-merged `ClearQueue`/`QueueJumpToIndex` and got renumbered in a later rebase the
  description was never updated for. Always read the live value straight from the daemon's
  `Opcode` enum (see `Opcode.kt`'s doc comment here for the current full picture) before wiring a
  new opcode.
- Uploaded images (the Share-intent image path, `app/src/main/java/org/castoff/control/upload/`)
  travel over a plain HTTP `POST /images` on its own port (46900 by default), not the FCast TCP
  control port — FCast's own frame cap is 32 KiB, nowhere near enough for a phone photo. Only the
  resulting `{id, url, container}` crosses into FCast proper (`Play`/`SetImageWallpaper`). See
  README's "Image sharing" section for the full flow; there is currently no in-app setting for a
  daemon operator's `CASTOFF_IMAGE_PORT` override, only the hardcoded default. Because that upload
  and the FCast control port are both plain, unencrypted local traffic to a host/IP the user types
  in at runtime (`HostSettings`), a static per-host Network Security Config isn't practical --
  `app/src/main/AndroidManifest.xml`'s `<application>` sets a blanket
  `android:usesCleartextTraffic="true"` instead, without which Android's default cleartext-traffic
  block (API 28+) silently fails every upload before any FCast frame is ever sent.
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
- The play queue is castoff's own private FCast extension (opcodes 14-19, beyond FCast v2's
  reserved 0-13 range: `RequestQueue`/`QueueState`/`QueueJumpForward`/`QueueJumpBackward`/
  `ClearQueue`/`QueueJumpToIndex`), hand-ported the same way as the rest of the protocol -- see the
  daemon's README "Queueing (private extension)" and `daemon/src/fcast.rs` for the authoritative
  shapes. Unlike `PlaybackUpdate`,
  `RequestQueue` *does* let a client ask for current state on demand, so `MainActivity` fetches the
  initial queue once via `FCastClient.requestQueue()` on a short-lived command connection right
  after the status connection reports `Connected`, rather than waiting for the next push -- the
  status connection itself stays read-only apart from its heartbeat (see above); it only ever
  decodes unprompted `QueueState` pushes (`FCastStatusListener.queueUpdates`). Next/Previous
  enablement (`ControlUiState.canQueueJumpForward`/`canQueueJumpBackward`) is derived from the
  daemon-reported `currentIndex`/`items` bounds on every `QueueState`, never guessed from a locally
  tracked count.
- Device behaviour here is reproducible without hardware: an AVD named `test_avd` and an Android SDK
  live under `/home/ai/android-sdk-test`, whose `adb-wrapped` and `steam-run`-wrapped `emulator`
  are the NixOS-safe entry points (the raw SDK binaries won't run under the standard dynamic
  linker); `steam-run` needs `NIXPKGS_ALLOW_UNFREE=1` in the environment or it refuses to evaluate.
  `/home/ai/android-sdk-test/fake_daemon.py` only acks commands and never pushes, so for
  connection/push work you need a stand-in that mirrors the daemon's rules -- no push at connect,
  push on state change, ~1/s while playing. `FCastStatusListenerTest` is the committed encoding of
  that contract at the socket level; for UI-level checks, drive the screen with `adb shell input`
  and assert what it says with `uiautomator dump`. A hand-rolled stand-in for UI-level checks can't
  assume "first accepted connection = the status link": `MainActivity`'s saved-host `DataStore`
  flow reliably emits once more shortly after the first connect, which restarts the status
  connection and leaves a fixed first-connection rule permanently pointed at the wrong socket for
  every connection after that. Classify by behavior instead -- a command connection sends its one
  frame immediately, while the status link stays silent until its first heartbeat.
- `app/build.gradle.kts`'s `signingConfigs { getByName("debug") { ... } }` block only overrides
  AGP's built-in auto-generated debug key when `ANDROID_DEBUG_KEYSTORE_PATH` /
  `ANDROID_DEBUG_KEYSTORE_PASSWORD` / `ANDROID_DEBUG_KEY_ALIAS` / `ANDROID_DEBUG_KEY_PASSWORD` are
  all set; a plain local `./gradlew assembleDebug` still uses Gradle's normal per-machine debug
  key untouched. `.github/workflows/ci.yml`'s `build` job sets those from the repo secrets
  `ANDROID_DEBUG_KEYSTORE_BASE64`/`ANDROID_DEBUG_KEYSTORE_PASSWORD`/`ANDROID_DEBUG_KEY_ALIAS`/
  `ANDROID_DEBUG_KEY_PASSWORD` (the keystore itself only exists as that base64 secret, decoded to
  `$RUNNER_TEMP` per run -- never commit a keystore file or its passwords to this repo), so every
  CI-built debug APK signs with the same key and installs as an upgrade over whatever a captain
  already has installed, instead of needing an uninstall each time.
- On NixOS (not GitHub Actions' Ubuntu runners), AAPT2's prebuilt binary won't run under the
  standard dynamic linker (`nix.dev/permalink/stub-ld`). Building locally under Nix needs an
  `-Pandroid.aapt2FromMavenOverride=<path to a wrapper named literally "aapt2">` pointing at a
  script that runs the real aapt2 through an FHS shim (e.g. `steam-run`); GitHub Actions CI needs
  none of this. `/home/ai/android-sdk-test/aapt2-wrapper/aapt2` is one such wrapper already set up
  on this machine (`steam-run` + `build-tools/35.0.0/aapt2`, with `NIXPKGS_ALLOW_UNFREE=1` needed
  in the environment as noted above) -- pass it straight to the Gradle property above rather than
  writing a new one, e.g. `./gradlew test -Pandroid.aapt2FromMavenOverride=/home/ai/android-sdk-test/aapt2-wrapper/aapt2`.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
