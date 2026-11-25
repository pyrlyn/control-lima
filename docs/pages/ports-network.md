# Ports & Network

Figma: TBD

## Purpose
Which host ports and IPs are in use, by what, and conflicts.

## Data shown
Port table: host IP:port, protocol, target (VM guest port / container port / host process), owner, state. Conflicts highlighted. IP list per VM and container. Networks (v1).

## Actions
Open in browser, copy, jump to owner, filter by owner/port.

## States
- **Empty:** "No forwarded or listening ports".
- **Loading:** skeleton.
- **Error:** `lsof` failed → table without host listeners, warning.
- **Unresponsive:** VM unresponsive → its forwards marked "VM unresponsive".

## API
`GET /api/ports`, `GET /api/vms`, `GET /api/containers`.
