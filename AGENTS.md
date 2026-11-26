# AGENTS.md

Rules for coding agents and contributors working on ControlLima.

## Design

**Figma file: https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima** (page "ControlLima":
"Screens · Dark" and "Screens · Light" sections, built from the Pyrlyn UI kit).

Never invent Figma URLs. A page without a frame yet says `Figma: TBD`. Colours, type and spacing in
`web/src/commonMain/kotlin/controllima/web/Theme.kt` come from the file's variables; change them there
when the file changes.

## Page docs (required)

- Every page or screen of the web UI has a file `docs/pages/<page>.md`. The list is in
  `docs/pages/README.md` and matches the `Page` enum in `web/src/commonMain/kotlin/controllima/web/App.kt`.
- Each page doc contains: purpose, data shown, actions, states (empty, loading, error, unresponsive),
  API endpoints used, and a `Figma:` line linking the exact frame or page
  (a URL with `node-id`, e.g. `https://www.figma.com/design/<fileKey>/ControlLima?node-id=12-345`).
- Update the page doc in the same change as the UI. A new page, dialog or screen gets a new doc in the
  same change. A removed page has its doc removed.
- When a Figma frame changes or moves, update the `Figma:` link.

## Language

All code, comments, commit messages and docs are in English. The UI is English only for now (see
`docs/plan/i18n-accessibility.md`).

## Dependencies

- Every third-party library sits behind our own interface (e.g. `HttpServer` for Ktor, `CommandRunner`
  for processes, `DockerTransport` for the Docker socket). Only the adapter files import the library.
- No new dependencies without approval. Approved for this project: Ktor (server, and client if needed),
  Compose Multiplatform, `kotlinx.*` (serialization, coroutines, datetime, html), the Kotlin stdlib.
  Anything else: propose it (why, size, license, alternative) and wait.
- Versions live only in `gradle/libs.versions.toml`.

## Tests

- A module's tests test only that module. Fakes replace other modules' interfaces.
- App-level (cross-module) tests live in the app modules (`server`, `cli`).
- Provider tests use recorded JSON fixtures in `src/test/resources`; they never run `limactl`, `colima`
  or Docker and never start a VM.
- `./gradlew build` must pass before a change is done.

## CI

GitHub Actions workflows are generated from `ci.main.kts` (`kotlin ci.main.kts`). Never edit
`.github/workflows/*.yml` by hand.

## Safety rules for the product

- Listen on localhost only; every `/api` and `/ws` call needs the token; check Origin; CSRF on writes.
- Never start a VM automatically. Start happens only on an explicit user action.
- Destructive actions require typing the target's name. Protected names (default `infra-test`) can never
  be deleted, by any role.
- Every external call has a timeout. A VM that reports Running but doesn't answer is `UNRESPONSIVE`.
- Use JSON output (`--json`, `--format json`) and the Docker API only; never parse human-readable text.

## Git

Small, logical commits. No force-push. Don't change remotes unless asked.
