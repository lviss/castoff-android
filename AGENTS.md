# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Build/run/test commands and current scope are in [README.md](README.md) — don't duplicate them here.
- The FCast wire protocol (`app/src/main/java/org/castoff/control/fcast/`) is hand-ported from
  [lviss/castoff](https://github.com/lviss/castoff)'s `daemon/src/fcast.rs`. The two repos are
  intentionally separate with no shared code or generated bindings, so a protocol change on the
  daemon side (new opcode, changed field) has to be re-read and re-applied here manually — there is
  no automated sync or contract test between the repos.
- `FCastClient` opens a new short-lived TCP connection per command rather than holding one open
  across the app's lifecycle; see the class doc comment in `FCastClient.kt` for why.
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
