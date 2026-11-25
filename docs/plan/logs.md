# Logs

## Sources

| Source | How |
|---|---|
| Container stdout/stderr | Docker `GET /containers/{id}/logs?follow=true&stdout=1&stderr=1&timestamps=1` (demultiplexed) |
| Lima host agent | `$LIMA_HOME/<vm>/ha.stdout.log`, `ha.stderr.log` (JSON lines; parsed for level/msg) |
| Lima serial console | `$LIMA_HOME/<vm>/serial*.log` |
| Guest journal | `journalctl -o json -f -n 500` over the persistent SSH session (systemd guests) |
| Guest cloud-init | `/var/log/cloud-init-output.log` |
| Colima | `~/.colima/<profile>/daemon/daemon.log` (path checked per version) |
| ControlLima | its own log and audit log |

## Viewer

- Follow mode, pause, jump to now; time range; search with highlight; level filter; stream filter
  (stdout/stderr); wrap toggle; download range.
- Virtualized list, capped at 50 000 lines in memory; older lines fetched on scroll.
- The host agent log is also used for health: "virtual machine is no longer live" → `UNRESPONSIVE`.
- Secrets redaction patterns applied before display (configurable).

## Limits

Guest journal needs a responsive VM; for unresponsive VMs the page shows host agent and serial logs.
