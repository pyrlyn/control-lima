# Metrics, history and charts

## What we measure

| Metric | Unit | Lima VM source | Container source |
|---|---|---|---|
| CPU | % of VM vCPUs | `/proc/stat` deltas in guest | Docker stats `cpu_stats` |
| CPU (host view) | % of host | host process (VZ/QEMU) CPU via `ps -o %cpu` | — |
| Memory used/total | bytes | `/proc/meminfo` (MemTotal − MemAvailable) | `memory_stats.usage − cache` |
| Memory RSS on host | bytes | host process RSS | — |
| Network rx/tx | bytes/s | `/proc/net/dev` (non-lo) | `networks.*` |
| Disk I/O read/write | bytes/s | `/proc/diskstats` | `blkio_stats` |
| Disk usage | bytes | `df -B1 /` in guest + host `diffdisk` file size | image/container size |
| Load average | — | `/proc/loadavg` | — |
| Probe latency | ms | probe round trip | — |

Collection interval: 2 s live (only while someone is watching or alerts need it), 15 s background.

## Collection

- One persistent SSH session per running Lima VM (from `limactl show-ssh --format=config`), a single
  shell command that prints all `/proc` files in one go; parsed with a tested parser.
- No collection for stopped VMs. An unresponsive VM produces no samples and an `UNRESPONSIVE` event.
- Containers: `GET /containers/{id}/stats?stream=true` per running container, limited to 50.

## Storage

- MVP: in-memory ring buffer per target and metric, 10 minutes at 2 s (300 points).
- v1: embedded SQLite (`~/.local/share/control-lima/metrics.db`) behind `MetricsStore` interface.
  The SQLite JDBC driver is a **new dependency that needs approval**; the fallback without new deps is an
  append-only binary file per target with our own compaction.

| Tier | Resolution | Retention |
|---|---|---|
| raw | 2–15 s | 24 h |
| 1m | 1 min (avg, max) | 7 d |
| 15m | 15 min (avg, max) | 30 d |
| 1h | 1 h (avg, max, p95) | 1 y |

Downsampling job runs every minute; retention purge hourly; DB size shown in Settings. Deleted VMs keep
history until retention or "forget history".

## Trends

- Disk growth per VM (linear regression over 7 d) and "full in N days".
- Memory pressure: share of time above 90 % in the last 24 h.
- Top consumers this week.

## Charts

- VM detail: CPU, memory, network, disk I/O, disk usage; 15 m / 1 h / 24 h / 7 d / 30 d.
- Metrics page: pick targets and metrics, overlay up to 6 series, CSV export.
- Drawn with Compose Canvas (no chart library). Tooltips, crosshair, gaps for missing data
  (unresponsive periods shaded).
