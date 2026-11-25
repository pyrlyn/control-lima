# Testing

- Unit tests per module test only that module; other modules are faked through their interfaces.
- Providers: recorded JSON fixtures in `src/test/resources` for several `limactl`/`colima`/Docker versions;
  a fixture per state (running, stopped, broken, missing fields, unknown fields).
- `CommandRunner`: real processes (`sh -c`) for timeout and kill behavior.
- Server: Ktor test host with fake providers (auth, Origin, error mapping, protected names).
- Web: page state logic in plain Kotlin view models tested on jvm; screenshot tests later.
- No test ever runs real `limactl`, `colima` or Docker, or starts a VM. An opt-in `integrationTest` task
  (v1) may run read-only commands against the developer's machine.
- CI: `ci.main.kts` → `.github/workflows/ci.yml`: build + tests, web distribution.
