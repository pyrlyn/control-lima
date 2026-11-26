# Containers

Figma: TBD

## Purpose
List and manage containers of Colima profiles (v2: nerdctl inside Lima VMs).

## Data shown
Table: state, name, image, status text, ports (host → container, clickable), IPs, created, CPU/mem mini-bars. Profile switcher, filters (state, image), search.

## Actions
Start, stop, restart (operator); rename (operator); delete (admin, confirm); logs; open detail.

## States
- **Empty:** "No containers in profile X".
- **Loading:** skeleton.
- **Error:** Docker socket missing (Colima stopped) → "Colima profile X is stopped" + Start button (explicit).
- **Unresponsive:** Docker API timeout → banner, last known list greyed.

## API
`GET /api/containers?profile=`, `POST /api/containers/{id}/start|stop|restart|rename`, `DELETE /api/containers/{id}`, `WS /ws/stats`.

## Implementation
`ContainersPage` in `web/.../DockerPages.kt`. A stopped Colima profile shows an explicit Start Colima button. On a Docker error the last known list stays visible, greyed.
