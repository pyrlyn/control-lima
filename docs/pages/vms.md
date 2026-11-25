# VMs

Figma: TBD

## Purpose
List and manage all Lima VMs and Colima profiles.

## Data shown
Table: status (icon + text), name, source, Lima home / profile, arch, VM type, CPUs, memory, disk (size and usage %), IPs, SSH port, live CPU/mem mini-bars, protected badge.
Filters: source, status, text search. Sort by any column.

## Actions
- Start, stop (operator), force stop (admin).
- Delete (admin) → [confirm dialog](confirm-dialog.md); disabled with tooltip for protected names.
- Rename (admin, v1, stopped VMs only), clone (v1), back up (operator, v1), new VM from template (v1).
- Open VM detail.

## States
- **Empty:** "No VMs yet" + "Create from template" (v1) and the CLI command.
- **Loading:** table skeleton.
- **Error:** source error row with message, retry; partial results kept.
- **Unresponsive:** row status "Unresponsive", actions limited to force stop, view logs.

## API
`GET /api/vms`, `POST /api/vms/{source}/{name}/start|stop`, `DELETE /api/vms/{source}/{name}`, `POST .../rename` (v1), `WS /ws/stats`, `WS /ws/events`.
