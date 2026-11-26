# VM detail

Figma: TBD

## Purpose
Everything about one VM: config, network, load over time, logs and actions.

## Data shown
- Header: name, status, source, uptime, protected badge, actions.
- Summary: arch, VM type, CPUs, memory, disk, mounts, Lima/Colima version, config path.
- Network: IPs, SSH port, port forwards (link to Ports & Network).
- Charts: CPU, memory, network rx/tx, disk I/O, disk usage; ranges 15 m (MVP) … 30 d (v1).
- Containers in this VM (Colima).
- Tabs (v1): Config (`lima.yaml` read-only, v2 editable), Logs, Backups, Alerts for this VM.
- Snapshots (v2): tags from `limactl snapshot list`; QEMU VMs only, otherwise the reason.

## Actions
Start, stop, delete (confirm), rename (v1), back up (v1), restore (v1), open shell (v2, opt-in), copy SSH command.
v2: take snapshot (operator), revert to a snapshot (admin, type the VM name), delete a snapshot (admin, type
the tag), edit `lima.yaml` of a stopped Lima VM (admin, validate, type the VM name).

## States
- **Empty:** n/a (404 → "VM not found", back to list).
- **Loading:** skeleton.
- **Error:** provider error banner; cached data shown greyed with timestamp.
- **Unresponsive:** red banner "VM reports Running but does not answer", charts show the gap, buttons: force stop, host agent log.

## API
`GET /api/vms/{source}/{name}`, actions under `/api/vms/{source}/{name}/…`, `GET /api/metrics` (v1), `WS /ws/stats`, `GET /api/logs/sources` (v1),
`…/snapshots`, `…/config`, `POST /api/lima/validate` (v2).

## Implementation
`VmDetailPage` in `web/.../VmPages.kt`, tabs Summary, Charts, Config, Logs, Backups, Alerts. Charts use `GET /api/metrics` for 1 h to 30 d and the live window from `WS /ws/stats` for 15 min; table view and CSV export use `ChartMath` from `shared/`.

Snapshots and Config editing live in `web/.../VmEditTabs.kt`. The Config tab shows the redacted file to
everyone; "Edit" loads the unredacted file (admin), "Validate" runs `limactl validate`, and "Save" asks for
the VM name, validates again on the server, backs up the old file and writes the new one atomically.
Revert to a snapshot is guarded like a delete because it discards the disk's current state.
