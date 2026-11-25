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
