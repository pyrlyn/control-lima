# Packaging, distribution, updates

- `./gradlew :cli:installDist` → `cli/build/install/control-lima/bin/control-lima` (JVM, needs JDK 21).
- `mise` task `start` in the repo.
- Homebrew tap `pyrlyn/tap` formula `control-lima` (depends on `openjdk@21`, `lima`; `colima` optional).
- GitHub Releases with zip of the distribution; checksums.
- v2: single native binary via Kotlin/Native or GraalVM native-image (evaluate; native-image is a tool, not
  a runtime dep).
- Update check: off by default; when on, GET GitHub releases API once a day, show a banner; never
  auto-update.
