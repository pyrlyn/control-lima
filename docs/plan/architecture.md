# Architecture

## Overview

```
browser (Compose wasmJs) ──HTTP/WS──▶ server (Ktor via HttpServer)
                                        │
                     ┌──────────────────┼────────────────────┬──────────────┐
                     ▼                  ▼                    ▼              ▼
              LimaProvider       ColimaProvider        DockerProvider   Store (metrics,
              (limactl --json)   (colima --json)       (Engine API on   alerts, audit;
                     │                  │               unix socket)     SQLite in v1)
                     └──── CommandRunner (timeouts) ────┘
```

## Modules

- `shared` (KMP: jvm, wasmJs): models (`Vm`, `Container`, `Image`, `PortForward`, `StatsSample`, ...),
  `ApiRoutes`, protected names, and in v1 the typed API client used by `web`. kotlinx.serialization.
- `server` (JVM): `HttpServer` interface; `KtorHttpServer` is the only Ktor-aware code. `Api` holds
  framework-free handlers. Later: `StatsCollector`, `MetricsStore`, `AlertEngine`, `AuditLog`.
- `providers/process`: `CommandRunner` with hard timeout and process-tree kill.
- `providers/lima`: `LimaProvider` (`list`, `probe`, later `start`, `stop`, `delete`, `logs`, `config`).
- `providers/colima`: `ColimaProvider` (`list`, `status`, `dockerSocket`, start/stop/delete).
- `providers/docker`: `DockerProvider` over `DockerTransport` (HTTP/1.0 on a unix socket, JDK only).
- `web` (wasmJs): Compose app; one composable per page (see `docs/pages`).
- `cli`: entry point; parses flags, passes everything after `--` to `limactl` and `colima`.

Every third-party library is behind an interface owned by us (`HttpServer`, `CommandRunner`,
`DockerTransport`, later `MetricsStore`, `Notifier`).

## VM state model

| State | Meaning |
|---|---|
| `RUNNING` | CLI says Running and the last probe answered |
| `UNRESPONSIVE` | CLI says Running but `limactl shell <vm> true` (or the SSH session) did not answer within 3 s, or the host agent log says the VM is no longer live |
| `STOPPED` | CLI says Stopped |
| `BROKEN` | CLI says Broken |
| `UNKNOWN` | anything else, or the CLI itself failed |

Probes run in the background (every 15 s per running VM, with backoff), never on the request path.

## Timeouts

- `limactl list --json`, `colima list --json`: 10 s.
- Probe: 3 s. Docker API GET: 5 s. Start/stop: 5 min, streamed progress. Delete: 2 min.
- A timed-out call never blocks other VMs: each VM is polled on its own coroutine.

## Data flow for live stats

`StatsCollector` keeps one persistent SSH session per running Lima VM (via `limactl show-ssh` config) and
reads `/proc/stat`, `/proc/meminfo`, `/proc/net/dev`, `/proc/diskstats` and `df -B1 /` every 2 s.
Containers use the Docker `/containers/{id}/stats?stream=true` API. Samples go to the in-memory ring buffer
and (v1) to `MetricsStore`, and are fanned out on `/ws/stats`.

## Launch

`control-lima` (cli) → parse flags → build providers with passthrough args → create `ServerConfig` with a
random token → start `KtorHttpServer` on 127.0.0.1 → print URL with token → optionally open the browser.
The web bundle is served from the server's classpath (v1: `server` packages `web` distribution).

## Configuration and data dirs

`~/.config/control-lima/config.json` (settings), `~/.local/share/control-lima/` (SQLite, audit log,
backups). Overridable with `--data-dir`. See [settings.md](settings.md).
