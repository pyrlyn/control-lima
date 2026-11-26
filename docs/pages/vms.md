# VMs

Figma: https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-447 (dark), https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-2478 (light)

## Purpose
List and manage all Lima VMs and Colima profiles.

## Data shown
Table: status (icon + text), name, source, Lima home / profile, arch, VM type, CPUs, memory, disk (size and usage %), IPs, SSH port, live CPU/mem mini-bars, protected badge.
Filters: source, status, text search. Sort by any column.

Remote hosts (v2, read-only): below the table, one block per host from Settings, with its ssh target, an
"unreachable" tag, the time of the last check and its VMs (status, name, source, arch, CPUs, memory, disk,
IPs). Hidden when no remote hosts are configured. Refreshed every 30 s.

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
- **Remote host unreachable:** the host keeps its block with the ssh error; other hosts are unaffected.

## API
`GET /api/vms`, `POST /api/vms/{source}/{name}/start|stop`, `DELETE /api/vms/{source}/{name}`, `POST .../rename` (v1), `WS /ws/stats`, `WS /ws/events`, `GET /api/remote-hosts` (v2).

## Implementation
`VmsPage` in `web/.../VmPages.kt`; sorting and filtering use `VmTable` from `shared/`. Row actions are the shared `VmActions` (start, stop, force stop, logs, rename, clone, back up, delete).

Remote hosts come from `RemoteHostsService` on the server. For each host it runs two fixed commands over the
user's own ssh (`ssh -o BatchMode=yes -o ConnectTimeout=5 … -- <target> 'limactl list --json'`, then
`colima list --json`) and reuses the local `LimaJson`/`ColimaJson` parsers. BatchMode means a host that would
ask for a password is reported as unreachable instead of hanging the server; the target is validated
(`Names.isValidSshTarget`) and placed after `--`, so it can never become an ssh option. Results are cached for
30 s. Remote VMs have no actions and no detail page, and their status is what the remote tool reports: they
are not probed, so a silent remote VM is not marked unresponsive. Managing remote VMs stays out of scope
(docs/plan/README.md, Non-goals).
