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
  continuously rather than once a second. It's read-only for now -- seeking isn't wired up.
- A Share-intent receiver: sharing a link (e.g. a YouTube video's "Share" -> "Castoff Control")
  sends an FCast `Play` with that URL to the configured TV box. This is the real Android share
  sheet path, not just something reachable programmatically.

That's it: no media browsing, no Jellyfin, no now-playing metadata (title/artist/artwork). See
[Not yet implemented](#not-yet-implemented-follow-up-work) below for what's planned but not built.

## What's here

- **`app/`** -- a standard Gradle/Kotlin Android app module, UI built with Jetpack Compose
  (Material 3).
- **`app/src/main/java/org/castoff/control/fcast/`** -- the FCast v2 client: wire framing
  (`Frame.kt`), message types (`Messages.kt`, `Opcode.kt`), a small per-command TCP sender
  (`FCastClient.kt`), a persistent read-only connection that receives daemon-pushed
  `PlaybackUpdate` frames (`FCastStatusListener.kt`), and the local position-interpolation rule
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
| `SetVolume` (8) | the control screen's volume slider |

`Seek`, `SetSpeed`, `Version`, and `Ping` are decoded on the wire-format level (see `Opcode.kt`)
but not yet wired to any UI action.

In the other direction, the client receives the daemon's `PlaybackUpdate` (6) pushes on a
persistent connection (`FCastStatusListener`) and uses them to drive the progress display above;
`VolumeUpdate` (7) and `PlaybackError` (9) are decoded on the wire-format level but not acted on
yet.

## Building and running

### Requirements

- JDK 17
- Android SDK (or just let Android Studio manage it)

### Build and test from the command line

```sh
./gradlew test          # unit tests (FCast framing, messages, playback interpolation)
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
   Save.
4. Tap Resume/Pause/Stop or drag the volume slider -- each sends an FCast frame to the daemon; the
   daemon needs something already loaded (via `Play`) for Resume/Pause/Stop to have an audible
   effect. Once something is playing, a progress bar with elapsed/total time appears and ticks
   forward from the daemon's `PlaybackUpdate` pushes.
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
- **Media browsing** beyond basic transport controls: no library, no queue, and no now-playing
  metadata (title/artist/artwork). Playback progress is shown now, but the volume slider is still
  local-only and doesn't reflect the daemon's `VolumeUpdate`.
- **App signing/release configuration.** Debug builds only; no keystore, no Play Store or F-Droid
  packaging.
- Anything to do with the daemon repo itself -- this app only ever talks to it over FCast.

## Credits / prior art

- [FCast](https://fcast.org/) / [futo-org/fcast](https://github.com/futo-org/fcast) -- the local
  control protocol this client implements a subset of.
- [lviss/castoff](https://github.com/lviss/castoff) -- the TV-box daemon this app controls.
