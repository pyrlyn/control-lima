# Overview

Figma: https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-389 (dark), https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-2420 (light)

## Purpose
The landing page: health of everything at a glance and the shortest path to a problem.

## Data shown
- Counters: VMs running / stopped / unresponsive / broken; containers running / exited; images and size.
- Firing alerts banner (v1).
- Tiles per running VM: name, source (Lima/Colima), status, CPU and memory sparklines (10 min), IP.
- Host: free disk on the `LIMA_HOME` volume, total memory given to VMs vs host memory.
- Recent events: state changes and actions (from `/ws/events`).

## Actions
- Click a tile → VM detail. Click a counter → filtered list.
- Start a stopped VM from its tile (operator; explicit click only).

## States
- **Empty:** no Lima or Colima found → install hints and links (`brew install lima colima`).
- **Loading:** skeleton tiles.
- **Error:** `limactl`/`colima` failed or timed out → per-source error card with message and retry; the other source still shows.
- **Unresponsive:** VM tile red with "Unresponsive since hh:mm", link to logs (host agent).

## API
`GET /api/vms`, `GET /api/containers`, `GET /api/images`, `WS /ws/stats`, `WS /ws/events`, `GET /api/alerts/events` (v1).

## Implementation
`OverviewPage` in `web/.../VmPages.kt`. Counters link to the filtered lists; stopped VMs get an explicit Start button only (never started automatically). Host section reads `GET /api/disk`.
