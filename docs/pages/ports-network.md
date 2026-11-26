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

## Implementation
`PortsPage` in `web/.../OpsPages.kt`. Also reads `GET /api/networks`. Conflicts are highlighted rows with a warning mark; owners link to their detail page.
v2: Kubernetes node-port services are listed as "k8s service" rows on 127.0.0.1 (Lima forwards them); they get no conflict check, since the VM's own forward of the same port is expected.
