# Container detail

Figma: TBD (no frame in the file yet)

## Purpose
One container: config, ports, load, logs.

## Data shown
Header (name, state, image link), command, env (secrets masked), mounts, networks and IPs, ports, restart policy and count, labels, live charts (CPU, memory, network, block I/O), logs tab.

## Actions
Start, stop, restart, rename, delete (confirm), open port in browser, reveal an env value (admin, audited).

## States
- **Empty:** 404 → "Container not found (deleted?)".
- **Loading:** skeleton.
- **Error:** Docker API error banner.
- **Unresponsive:** Docker API timeout; live charts paused.

## API
`GET /api/containers/{id}`, container actions, `WS /ws/stats`, `WS /ws/logs`.

## Implementation
`ContainerDetailPage` in `web/.../DockerPages.kt`, tabs Summary, Charts, Logs. Masked environment values are revealed one at a time by an admin (`POST /api/containers/{id}/reveal`, audited).
