# Logs

Figma: TBD

## Purpose
Read and follow logs of containers, Lima host agents, serial consoles and guest journals.

## Data shown
Source picker (target + source type), virtualized log list with timestamps, level, stream; search highlight; follow indicator.

## Actions
Follow/pause, time range, search, level and stream filters, wrap, download range.

## States
- **Empty:** "No log lines in this range".
- **Loading:** spinner while backfilling.
- **Error:** source unavailable (file missing, Docker error).
- **Unresponsive:** guest journal unavailable → suggests host agent and serial logs.

## API
`GET /api/logs/sources`, `WS /ws/logs`.

## Implementation
`LogsPage` in `web/.../LogsPage.kt`; also embedded in the VM and container detail tabs. Follow streams `WS /ws/logs`; Pause switches to a static tail from `GET /api/logs`. The browser keeps the last 5000 lines. When the guest journal is unavailable the page offers the host agent and serial logs.
v2: pod logs (`kube:<source>/<vm>/<namespace>/<pod>#pod:<container>`) are reached from the Kubernetes page; they are a tail without live follow.
