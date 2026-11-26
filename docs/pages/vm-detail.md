# VM detail

Figma: https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-526 (dark), https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-2557 (light)

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
- Terminal (v2, opt-in): a login shell in a running VM, admins only, shown only when Settings turns it on.

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
`…/snapshots`, `…/config`, `POST /api/lima/validate` (v2), `WS /ws/terminal` (v2).

## Implementation
`VmDetailPage` in `web/.../VmPages.kt`, tabs Summary, Charts, Config, Logs, Backups, Alerts. Charts use `GET /api/metrics` for 1 h to 30 d and the live window from `WS /ws/stats` for 15 min; table view and CSV export use `ChartMath` from `shared/`.

Snapshots and Config editing live in `web/.../VmEditTabs.kt`. The Config tab shows the redacted file to
everyone; "Edit" loads the unredacted file (admin), "Validate" runs `limactl validate`, and "Save" asks for
the VM name, validates again on the server, backs up the old file and writes the new one atomically.
Revert to a snapshot is guarded like a delete because it discards the disk's current state.

The Terminal tab lives in `web/.../TerminalTab.kt`. It connects only on "Connect", never reconnects on its
own, and never starts a stopped VM. The server runs `ssh -tt` with the VM's own Lima `ssh.config` and
`stty sane iutf8 rows R cols C; exec $SHELL -l` in the guest; the guest pty would otherwise start raw
(no echo, no Ctrl-C), because the server's side is a pipe. That is also why the size is fixed per session:
ssh cannot resize a pty without a local terminal, so picking 80×24, 120×32 or 160×48 opens a new session.
This replaces the plan's `limactl shell` / `script` idea: no local pty and no new dependency.
`TerminalScreen` in `shared/` emulates the VT100/xterm subset a shell, `less`, `top` and `vi` use, and the
canvas draws it on a fixed cell grid because Compose for the browser has no monospace font. Keys come from
DOM `keydown` and `paste` events while the terminal holds the keyboard ("Release keyboard" gives it back to
the page). IME composition is not supported. At most four sessions run at once; each open and close is
audited with its duration.
