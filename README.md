# castoff-android

Native Kotlin Android control app for [castoff](https://github.com/lviss/castoff): an open-source,
appliance-like Chromecast/Roku alternative for a boat. This repo contains the first working
scaffold: a control screen for playback on the TV box, and an Android "Share" intent receiver so
sharing a link from another app (e.g. YouTube) casts it. Nothing else exists yet -- see
[Not yet implemented](#not-yet-implemented-follow-up-work) below.

This repo is kept deliberately separate from the daemon repo
([lviss/castoff](https://github.com/lviss/castoff)): the two only interact over FCast, a fixed
external wire protocol, not something that needs monorepo coupling, and the toolchains
(Nix/Rust vs. Gradle/Kotlin) don't mix well in one CI pipeline.

## What works today

- A control screen: resume/pause toggle, stop, and a volume slider, all sent as FCast commands to
  a TV box at a host/port you type in once (persisted locally). No mDNS/auto-discovery yet -- see
  below.
- A live playback progress bar on that screen, fed by `PlaybackUpdate` frames the daemon pushes
  over a persistent connection and interpolated locally between pushes, so it advances
  continuously rather than once a second. Dragging it sends a `Seek` once the drag finishes
  (not on every intermediate tick, the same pattern the volume slider already used), and shows
  the dragged position rather than fighting the live interpolation while the drag is in progress.
- A connection indicator on that screen that always says whether the app is talking to the TV
  box (connected / connecting / not connected), with the concrete reason when it isn't and a
  Connect button for the not-connected case. The status connection is opened automatically on
  app start with a saved host and replaced on every return to the foreground, so a link the OS
  or the network silently killed while the app was backgrounded doesn't leave a stale screen.
  Opening the app while something is already playing therefore shows the playback state and the
  live progress bar once the daemon's next push arrives (about a second).
- A Share-intent receiver: sharing a link (e.g. a YouTube video's "Share" -> "Castoff Control")
  sends an FCast `Play` with that URL to the configured TV box. This is the real Android share
  sheet path, not just something reachable programmatically.
- A play queue list on the control screen, showing what the daemon has queued in order with the
  current item highlighted, plus Next/Previous buttons that jump forward/backward in it, a button
  to clear the queue, and tapping a row to jump straight to it. Fed live by the daemon's
  `QueueState` pushes (castoff's private FCast extension, see below) over the same persistent
  connection the playback progress bar uses, plus an initial fetch on connect since, unlike
  playback state, the queue can be asked for on demand. Next/Previous disable at either end of the
  queue based on the daemon's own reported position, not a locally guessed count. Each row shows
  the daemon-resolved title and length once its background lookup completes, falling back to the
  raw URL until then (or forever, if the lookup fails or the URL isn't lookup-able).

That's it: no media browsing, no Jellyfin, no now-playing metadata (title/artist/artwork) for the
currently playing item. See [Not yet implemented](#not-yet-implemented-follow-up-work) below for
what's planned but not built.

## What's here

- **`app/`** -- a standard Gradle/Kotlin Android app module, UI built with Jetpack Compose
  (Material 3).
- **`app/src/main/java/org/castoff/control/fcast/`** -- the FCast v2 client: wire framing
  (`Frame.kt`), message types (`Messages.kt`, `Opcode.kt`), a small per-command TCP sender
  (`FCastClient.kt`, also used for the queue commands), a persistent status connection that
  receives daemon-pushed `PlaybackUpdate`/`QueueState` frames and reports its own connection
  lifecycle (`FCastStatusListener.kt`), and the local position-interpolation rule
  (`PlaybackPosition.kt`).
- **`app/src/main/java/org/castoff/control/settings/`** -- `HostSettings.kt`, a DataStore-backed
  store for the user-configured TV box host/port.
- **`app/src/main/java/org/castoff/control/ui/`** -- the Compose control screen.
- **`app/src/main/java/org/castoff/control/share/`** -- `ShareReceiverActivity.kt`, the
  `ACTION_SEND` handler.

### Why Kotlin + Jetpack Compose

Native Kotlin was the brief from the start (this being the native Android counterpart to the
daemon's native Rust). Jetpack Compose over classic XML views/fragments for the same reason the
daemon picked Rust/tokio over a heavier stack: this is a small, mostly single-screen control
surface, and Compose's declarative model keeps that screen's state (host, volume, play/pause) and
its FCast side effects easy to follow in one place without a fragment/XML layout/ViewModel
boilerplate split.

### Why FCast, and what's implemented

Like the daemon, this client speaks a subset of [FCast](https://fcast.org/) (protocol v2, see
[docs.fcast.org/protocol/v2](https://docs.fcast.org/protocol/v2)): a TCP connection on port
`46899` by default, each message framed as a 4-byte little-endian length prefix + a 1-byte opcode
+ an optional UTF-8 JSON body (`length` = 1 + body size, max 32 KiB). See
[`app/src/main/java/org/castoff/control/fcast/Frame.kt`](app/src/main/java/org/castoff/control/fcast/Frame.kt)
for the exact framing, matching the daemon's
[`daemon/src/fcast.rs`](https://github.com/lviss/castoff/blob/main/daemon/src/fcast.rs) byte for
byte.

Opcodes this client sends:

| Opcode | Sent when |
| --- | --- |
| `Play` (1) | a link is shared to this app via `ACTION_SEND` |
| `Pause` (2) / `Resume` (3) | the control screen's play/pause toggle |
| `Stop` (4) | the control screen's stop button |
| `Seek` (5) | dragging the control screen's playback progress slider |
| `SetVolume` (8) | the control screen's volume slider |
| `Ping` (12) | the status connection's heartbeat, sent on a fixed cadence to tell an idle daemon apart from a dead link |
| `RequestQueue` (14, private extension) | once, right after the status connection reports `Connected`, to seed the queue list |
| `QueueJumpForward` (16, private extension) | the control screen's Next button |
| `QueueJumpBackward` (17, private extension) | the control screen's Previous button |
| `ClearQueue` (18, private extension) | the control screen's clear-queue button |
| `QueueJumpToIndex` (19, private extension) | tapping an item in the control screen's queue list |

`SetSpeed` and `Version` are decoded on the wire-format level (see `Opcode.kt`) but not yet wired
to any UI action.

In the other direction, the client receives the daemon's `PlaybackUpdate` (6) pushes on a
persistent connection (`FCastStatusListener`) and uses them to drive the progress display above;
`VolumeUpdate` (7) and `PlaybackError` (9) are decoded on the wire-format level but not acted on
yet. `QueueState` (15, private extension) pushes on that same connection drive the queue list, the
same push-on-change model `PlaybackUpdate` uses.

Opcodes 14-19 (`RequestQueue`/`QueueState`/`QueueJumpForward`/`QueueJumpBackward`/`ClearQueue`/
`QueueJumpToIndex`) are castoff's own private FCast extension for the play queue -- FCast v2 itself
has no queue concept -- mirroring the daemon's `daemon/src/fcast.rs`; see its README's "Queueing
(private extension)" section for the full contract (queueing instead of interrupting, auto-advance,
persistence across a restart).

## Building and running

### Requirements

- JDK 17
- Android SDK (or just let Android Studio manage it)

### Build and test from the command line

```sh
./gradlew test          # unit tests (FCast framing, messages, playback interpolation, queue commands)
./gradlew assembleDebug  # builds app/build/outputs/apk/debug/app-debug.apk
```

### Run it

The easiest way to see it working end-to-end without hardware:

1. Run `nix build .#castoff-daemon && ./result/bin/castoff-daemon` from the
   [daemon repo](https://github.com/lviss/castoff) on any machine on your network (or `cargo run`
   from its dev shell). It listens on `0.0.0.0:46899`.
2. Open this project in Android Studio and run it on an emulator (API 26+) or a real device on the
   same network/Wi-Fi as the daemon.
3. On first launch, enter the daemon machine's IP and port `46899` in the control screen and tap
   Save. The line at the top of the screen then says `Connected to <host>:<port>`; if it says
   `Not connected` instead, it names the reason (`connection refused` when the daemon isn't
   running, `can't resolve that host name` for a typo) and offers a Connect button.
4. Tap Resume/Pause/Stop or drag the volume slider -- each sends an FCast frame to the daemon; the
   daemon needs something already loaded (via `Play`) for Resume/Pause/Stop to have an audible
   effect. Once something is playing, a progress bar with elapsed/total time appears and ticks
   forward from the daemon's `PlaybackUpdate` pushes; dragging it sends a `Seek` once released.
5. To test the share path: share a URL to a media file (e.g. long-press a link and choose "Share",
   or share from a browser/YouTube) and pick "Castoff Control" from the share sheet. That sends
   `Play` with that URL.
6. To test manually without a second app, `adb shell am start -a android.intent.action.SEND -t
   text/plain --es android.intent.extra.TEXT "https://example.com/video.mp4" -n
   org.castoff.control/.share.ShareReceiverActivity` triggers the same handler from the command
   line.

An emulator can reach a daemon running on the host machine at `10.0.2.2` (the emulator's alias for
the host loopback interface) if you're running the daemon locally rather than on separate
hardware.

### Sideloading

No release signing is set up yet (see below), so the only way to install this today is a debug
build: `./gradlew installDebug` with a device/emulator connected via `adb`, or copying
`app-debug.apk` over and installing it directly (enable "install unknown apps" for whichever app
you use to open it). CI (`.github/workflows/ci.yml`) also builds a debug APK on every PR and on
push to `main`, and uploads it as a workflow run artifact -- see the "Artifacts" section at the
bottom of the run's summary page on GitHub Actions.

## Not yet implemented (follow-up work)

Out of scope for this scaffold, deliberately:

- **mDNS/auto-discovery** of the TV box. The daemon already advertises itself via Avahi (see
  [`nix/tv-box.nix`](https://github.com/lviss/castoff/blob/main/nix/tv-box.nix) in the daemon
  repo); wiring this app up to discover it automatically instead of typing in an IP is a natural
  next step, not done here.
- **Jellyfin-specific UI.** The daemon doesn't speak Jellyfin yet either -- this app only ever
  sends a bare `url`.
- **Media browsing** beyond basic transport controls and the queue list: no library, and no
  now-playing metadata (title/artist/artwork) for the currently playing item -- the queue list
  does show the daemon-resolved title/length per item (see above), but the "Playback" section
  above it has no equivalent for the current item, only its progress time. Playback progress is
  shown now, but the volume slider is still local-only and doesn't reflect the daemon's
  `VolumeUpdate`.
- **A connect-time status request.** FCast v2 (and this daemon) has no "tell me your current
  state" request: a freshly connected sender learns playback state only from the next
  `PlaybackUpdate`, which the daemon sends on a state change and about once a second while playing.
  Connecting while playback is paused or idle therefore means the app knows nothing until
  something changes, and it says so rather than guessing -- see `FCastStatusListener`'s doc and
  AGENTS.md. Fixing that properly would be a protocol/daemon change, out of scope here.
- **App signing/release configuration.** Debug builds only; no keystore, no Play Store or F-Droid
  packaging.
- Anything to do with the daemon repo itself -- this app only ever talks to it over FCast.

## Credits / prior art

- [FCast](https://fcast.org/) / [futo-org/fcast](https://github.com/futo-org/fcast) -- the local
  control protocol this client implements a subset of.
- [lviss/castoff](https://github.com/lviss/castoff) -- the TV-box daemon this app controls.
