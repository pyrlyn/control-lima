# Packaging, distribution, updates

- `./gradlew :cli:installDist` → `cli/build/install/control-lima/bin/control-lima` (JVM, needs JDK 21).
- `mise` task `start` in the repo.
- Homebrew tap `pyrlyn/tap` formula `control-lima` (depends on `openjdk@21`, `lima`; `colima` optional).
- GitHub Releases with zip of the distribution; checksums.
- v2: single native binary via Kotlin/Native or GraalVM native-image (evaluate; native-image is a tool, not
  a runtime dep).
  Evaluated in v2, not built:
  - Kotlin/Native would mean a second server: Exposed, sqlite-jdbc, `ProcessBuilder`, the JDK HTTP client
    and the Netty engine are JVM-only, so every service and provider would need a native rewrite.
  - GraalVM native-image keeps the code, but needs reachability metadata for Netty, kotlinx.serialization,
    Exposed (reflection over table objects) and sqlite-jdbc's JNI library, a GraalVM toolchain in CI, and
    one build per OS. Startup time is not a problem for a long-running local server, so the gain is only
    "no JDK to install"; Homebrew's `openjdk@21` dependency covers that today.
  - Decision: stay on the JVM distribution; revisit if users ask for a JDK-free install.
- Update check: off by default; when on, GET GitHub releases API once a day, show a banner; never
  auto-update.
