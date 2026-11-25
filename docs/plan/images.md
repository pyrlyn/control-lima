# Images

- List per Colima profile (Docker API `/images/json`), later per Lima VM with nerdctl.
- Columns: repository:tag (all tags), ID, size, created, used by (containers), dangling flag.
- Detail: layers (`/images/{id}/history`), labels, architecture, which containers use it.
- Actions (v1): pull with progress, tag, delete (refuses in-use images; admin; confirm), prune dangling or
  unused with a dry-run preview of freed space.
- Totals: images size, reclaimable size (from `/system/df`).
- Later: registry login status, vulnerability scan (would need a new tool, needs approval).
