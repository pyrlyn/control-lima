# Desktop app (later)

- Compose Multiplatform desktop (`jvm` target of `web`'s UI code moved to `commonMain`), wrapping the
  same server in-process.
- macOS menu bar item: VMs with status dots, quick start/stop, alert count, "Open dashboard".
- Starts at login only if the user enables it. Still never starts VMs on its own.
- Signed and notarized DMG via GitHub releases (needs an Apple developer account).
