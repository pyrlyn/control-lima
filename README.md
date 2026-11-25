# ControlLima

A local control panel and dashboard for [Lima](https://lima-vm.io) and [Colima](https://github.com/abiosoft/colima).
One command starts a localhost-only web UI that shows your VMs and containers, their ports, IPs, images
and live load, and lets you start, stop, rename and delete them safely.

Status: **skeleton**. The plan is complete (see [docs/plan](docs/plan/README.md)); the code is a buildable
skeleton with a health endpoint, a VM list backed by `limactl list --json`, and an empty web app shell.

## Quick start

```sh
./gradlew :cli:run --args='--open'              # http://127.0.0.1:8787/?token=<random>
./gradlew :cli:run --args='--port 9000 -- --debug'   # everything after -- goes to limactl/colima
```

Options: `--port`, `--lima-home`, `--colima-profile`, `--open`, `--help`. ControlLima never starts a VM
on its own and listens on `127.0.0.1` only.

## Modules

| Module | What it is |
|---|---|
| `shared` | KMP (jvm + wasmJs): models, API routes, protected names; kotlinx.serialization |
| `server` | Ktor (Netty) behind our `HttpServer` interface; `/health`, `/api/vms` |
| `providers/process` | `CommandRunner`: runs CLIs with a hard timeout |
| `providers/lima` | `LimaProvider` over `limactl ... --json` |
| `providers/colima` | `ColimaProvider` over `colima ... --json` |
| `providers/docker` | `DockerProvider` over the Docker Engine API on the Colima unix socket |
| `web` | Compose Multiplatform for Web (wasmJs) UI |
| `cli` | `control-lima` entry point, flag parsing, `--` passthrough |

## Build and test

```sh
./gradlew build                               # everything, all tests
./gradlew :web:wasmJsBrowserDistribution      # web bundle in web/build/dist/wasmJs/productionExecutable
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
