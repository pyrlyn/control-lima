# Disk usage and cleanup

- Overview: host volume of `LIMA_HOME` (free/total), per-VM disk files (`basedisk`, `diffdisk` actual
  allocated size, not apparent size), guest `df` usage, Docker `/system/df` (images, containers,
  volumes, build cache).
- Reclaim suggestions with sizes: dangling images, stopped containers, unused volumes (listed, never
  auto-deleted), build cache, `fstrim` inside guest to shrink sparse disks, old ControlLima backups and
  metrics history.
- Every cleanup is a dry run first, then confirm; admin only; audited.
- Trend: VM disk growth (see metrics.md).
