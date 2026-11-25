# Backup and restore

## What is backed up

| Item | Source | Notes |
|---|---|---|
| Lima VM config | `$LIMA_HOME/<vm>/lima.yaml` | plus `override.yaml`/`default.yaml` from `$LIMA_HOME/_config` |
| Colima profile | `~/.colima/<profile>/colima.yaml` | |
| ControlLima settings | config file, alert rules, templates | secrets excluded |
| VM disks | not by default | `diffdisk` can be huge; v2 via snapshots |

## Backups

- Manual ("Back up now" for one or all VMs) and scheduled (daily/weekly, keep N).
- Stored as `~/.local/share/control-lima/backups/<timestamp>-<name>.tar.gz` with a manifest
  (`manifest.json`: versions of limactl/colima, checksums, which VMs).
- Secrets in configs (e.g. env, provision scripts with tokens) are detected by pattern and flagged; the
  user chooses to keep or redact them before the backup is written.
- Export/download and import/upload a backup archive.

## Restore

- Pick backup → pick targets → see a diff against the current config → type the VM name → restore.
- Restore of a running VM: config is written, a banner says "restart required"; ControlLima does not
  restart it automatically.
- Restore to a new name = create VM from that config (`limactl create --name <new> <file>`), not started.
- Never overwrites a protected VM without admin + typed confirmation.

## Snapshots (v2)

`limactl snapshot create|apply|delete|list` where the VM type supports it (QEMU); hidden for VZ until
Lima supports it. Colima has no snapshots.
