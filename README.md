# ControlLima

A local control panel and dashboard for [Lima](https://lima-vm.io) and [Colima](https://github.com/abiosoft/colima).
One command starts a localhost-only web UI that shows your VMs and containers, their ports, IPs, images
and live load, and lets you start, stop, rename and delete them safely.

Status: **MVP and v1 implemented**, not released yet. Everything in [docs/plan](docs/plan/README.md) up to
v1 is in place: inventory, live load over WebSocket, actions with type-to-confirm, charts and history,
alerts and notifications, logs, templates, backups and restore, tokens, users and the audit log. v2 items
(Kubernetes, desktop app, web terminal, remote hosts) are not started.

## Quick start

```sh
./gradlew :cli:run --args='--open'              # http://127.0.0.1:8787/?token=<random>
./gradlew :cli:run --args='--port 9000 -- --debug'   # everything after -- goes to limactl/colima
```

Options: `--port`, `--lima-home`, `--colima-profile`, `--data-dir`, `--open`, `--help`. ControlLima never
starts a VM on its own and listens on `127.0.0.1` only.

Other commands run the same services without the web server:

```sh
control-lima status [--json]                         # VMs and containers; exit 3 when Lima is missing
control-lima top                                     # live CPU, memory and disk in the terminal
control-lima token create --name ci --role viewer --expires 30d
control-lima token list | revoke <id>
control-lima backup [vm…] [--redact]
control-lima restore <backup-id> --target vm:lima/dev --confirm dev [--new-name dev2]
```

`./gradlew :cli:installDist` builds `cli/build/install/control-lima/bin/control-lima` with the web app inside.

## Modules

| Module | What it is |
|---|---|
| `shared` | KMP (jvm + wasmJs): models, API routes and typed client, validation, formatting, chart math |
| `server` | Ktor (Netty) behind our `HttpServer` interface: `/api/*`, `/ws/*`, access control, services |
| `providers/process` | `CommandRunner`: runs CLIs with a hard timeout |
| `providers/lima` | `LimaProvider` over `limactl ... --json` |
| `providers/colima` | `ColimaProvider` over `colima ... --json` |
| `providers/docker` | `DockerProvider` over the Docker Engine API on the Colima unix socket |
| `providers/host` | Host facts: free disk, listening ports, opening the browser |
| `web` | Compose Multiplatform for Web (wasmJs) UI, one page per file in `docs/pages/` |
| `cli` | `control-lima` entry point: `serve`, `status`, `token`, `backup`, `restore`, `top`; bundles the web app |

## Build and test

```sh
./gradlew build                               # everything, all tests, web bundle packed into the CLI jar
./gradlew :web:wasmJsBrowserDistribution      # web bundle alone, in web/build/dist/wasmJs/productionExecutable
kotlin ci.main.kts                            # regenerate .github/workflows/ci.yml
```

Provider tests use recorded JSON fixtures and never start VMs.

## Docs

- [Product plan](docs/plan/README.md): phases MVP → v1 → v2, architecture, API, metrics, alerts, auth, security, risks.
- [Pages](docs/pages/README.md): one file per screen.
- [AGENTS.md](AGENTS.md): rules for contributors and coding agents.
- [PROMPT.md](PROMPT.md): the implementation prompt for an agent.

## Design

<!-- FIGMA-PLACEHOLDER: replace this line with the real file URL once it exists. -->
**Figma file: TBD (to be created)**

Each page links its own frame in `docs/pages/<page>.md` (`Figma:` line).

## License

TBD.
