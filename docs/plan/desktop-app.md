# Desktop app (v2)

- Compose Multiplatform desktop (`jvm` target of `web`'s UI code moved to `commonMain`), wrapping the
  same server in-process.
- macOS menu bar item: VMs with status dots, quick start/stop, alert count, "Open dashboard".
- Starts at login only if the user enables it. Still never starts VMs on its own.
- Signed and notarized DMG via GitHub releases (needs an Apple developer account).

## Implementation (v2)

- `:web` has a `jvm` target: pages are in `commonMain`; `jvmMain` implements the platform functions of
  `Platform.kt` on the JDK and AWT (`java.net.http` for HTTP and WebSockets, `FileDialog` for downloads,
  uploads and CSV export, the AWT clipboard, `Desktop.browse` for forwarded ports) and the terminal
  keyboard on Compose key events. No new dependency.
- `:desktop` (`./gradlew :desktop:run`) starts `ControlLimaServer` on a free loopback port with a fresh
  launch token, then shows the UI against it. The token never leaves the process.
- Menu bar item: a dot (green with a VM running, grey with none, red while an alert fires); the menu lists
  every VM with its status, Start/Stop/Details per VM, the firing alert count and "Open dashboard".
  Closing the window keeps the app in the menu bar; Quit is in the menu.
- Not done: start at login (no switch yet), and packaging. Compose's `packageDmg` could build an unsigned
  DMG, but a signed and notarized one needs an Apple developer account, so there is no release artifact.
