# ControlLima product plan

ControlLima is a local control panel and dashboard for Lima VMs, Colima profiles and the containers and
images inside them. It starts with one command, runs on the user's Mac (or Linux host), listens on
localhost only and wraps `limactl`, `colima` and the Docker Engine API.

## Goals

- See everything at a glance: VMs, containers, ports, IPs, images, live and historical load.
- Act safely: start, stop, delete, rename, with confirmation and protected names.
- Notice problems: VM down or unresponsive, disk almost full, high load, with notifications.
- Keep configs safe: backups, restore, templates for new VMs.
- Never surprise the user: no automatic VM start, no network exposure, no telemetry.

## Non-goals

- Remote multi-host fleet management (maybe v3, see [roadmap](#v2)).
- Replacing `limactl`/`colima`; ControlLima calls them and shows what they do.
- Running as a hosted service.

## Documents

| File | Topic |
|---|---|
| [architecture.md](architecture.md) | modules, providers, data flow, timeouts, state model |
| [api.md](api.md) | REST and WebSocket API |
| [metrics.md](metrics.md) | live load, history, storage, downsampling, retention, trends, charts |
| [alerts.md](alerts.md) | rules, VM down/unresponsive, disk, notifications |
| [auth.md](auth.md) | token access, roles, multi-user, audit log |
| [security.md](security.md) | threat model, localhost, Origin/CSRF, protected names |
| [backup-restore.md](backup-restore.md) | export/import of `lima.yaml` and Colima profiles, snapshots |
| [templates.md](templates.md) | ready VM setups and custom templates |
| [logs.md](logs.md) | container logs, Lima host-agent/serial logs, guest journal |
| [images.md](images.md) | images, usage, cleanup |
| [ports-network.md](ports-network.md) | ports, forwards, IPs, conflicts |
| [disk-cleanup.md](disk-cleanup.md) | disk usage overview, prune, VM disk growth |
| [settings.md](settings.md) | settings, multiple `LIMA_HOME`, Colima profiles, config file |
| [kubernetes.md](kubernetes.md) | k8s in Colima (later) |
| [cli-tui.md](cli-tui.md) | CLI subcommands and TUI (later) |
| [desktop-app.md](desktop-app.md) | menu bar / desktop app (later) |
| [packaging-updates.md](packaging-updates.md) | Homebrew, mise, releases, update check |
| [i18n-accessibility.md](i18n-accessibility.md) | English only for now, accessibility |
| [privacy-telemetry.md](privacy-telemetry.md) | telemetry off, local data only |
| [testing.md](testing.md) | test strategy, fixtures, CI |
| [risks.md](risks.md) | technical risks and mitigations |
| [ui-restyle.md](ui-restyle.md) | bringing the web UI in line with the Figma design |
| [../pages/README.md](../pages/README.md) | one doc per screen |

## Phases

### MVP (0.1)

- One-command launch: `control-lima [--port] [--lima-home] [--colima-profile] [--open] [-- passthrough]`.
- Lima and Colima VM list: status (incl. `UNRESPONSIVE`), CPU, RAM, disk, arch, VM type, SSH port, IPs.
- Colima containers: state, image, ports, IPs.
- Ports table: Lima port forwards, container ports, host listeners.
- Live load per VM and container (CPU, memory) over WebSocket every 2 s, charts for the last 10 minutes
  (in-memory ring buffer).
- Actions: start/stop VM and container, delete with type-the-name confirmation, container rename.
- Images: list, size, tags, used-by.
- Security baseline: localhost only, random token, Origin check, CSRF token, protected names, audit log file.
- Pages: Overview, VMs, VM detail, Containers, Container detail, Images, Ports & Network.

### v1 (1.0) — full dashboard

- Metrics: CPU, memory, network, disk I/O and disk usage per VM and container; history in embedded
  SQLite with downsampling (raw 2 s → 1 min → 15 min → 1 h) and retention; trends (7-day disk growth,
  "disk full in N days").
- Charts page with time ranges (15 m, 1 h, 24 h, 7 d, 30 d), compare VMs, export CSV.
- Alerts: rules (threshold + duration), VM down/unresponsive, disk almost full, container restart loops;
  notifications: in-app, macOS notification, webhook; optional Telegram and Slack.
- Access: named tokens with roles viewer/operator/admin, optional multi-user (local accounts), audit log
  page.
- Backup/restore of VM configs (`lima.yaml`, Colima profile YAML), scheduled backups, diff before restore.
- Templates: built-in (Docker host, k3s, Ubuntu dev, Alpine minimal, x86_64 on ARM via Rosetta/QEMU) and
  custom; create VM from template with preview.
- Logs viewer: container logs (follow, search, download), Lima `ha.stdout/ha.stderr/serial*.log`, guest
  `journalctl` over the persistent SSH session.
- Images: delete, prune with preview, pull.
- Disk cleanup page, settings page, multiple `LIMA_HOME`, Colima profiles switcher.
- VM rename (guarded), VM clone where Lima supports it.
- Packaging: Homebrew tap, `mise` task, GitHub releases; update check (opt-in).
- Pages: + Metrics, Alerts, Logs, Templates, Backups, Settings & Access.

### v2

- Kubernetes in Colima: nodes, pods, services, port-forward.
- nerdctl/containerd containers inside Lima VMs.
- Web terminal (`limactl shell`) with explicit opt-in.
- Snapshots (`limactl snapshot`) where supported.
- Edit `lima.yaml` in the UI with schema validation.
- CLI subcommands and TUI (`control-lima status`, `control-lima top`).
- Desktop app (Compose desktop) with menu bar status.
- Remote hosts over SSH (read-only first).
