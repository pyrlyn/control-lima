# Metrics

Figma: TBD

## Purpose
Charts and history across VMs and containers; trends.

## Data shown
Target picker, metric picker (CPU, memory, network, disk I/O, disk usage, load, probe latency), range (15 m, 1 h, 24 h, 7 d, 30 d, custom), overlay up to 6 series, trend cards (disk full in N days, top consumers), table view.

## Actions
Change range/targets, zoom, export CSV, forget history for a target (admin).

## States
- **Empty:** no history yet → "Collecting… first points in a few seconds".
- **Loading:** chart skeleton.
- **Error:** store error banner.
- **Unresponsive:** shaded gaps for periods a VM was unresponsive.

## API
`GET /api/metrics`, `GET /api/metrics/trends`, `WS /ws/stats`.
