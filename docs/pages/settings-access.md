# Settings & Access

Figma: https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-2230 (dark), https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-4261 (light)

## Purpose
Configure ControlLima and manage who can do what.

## Data shown
Sections: General (Lima homes, Colima profiles, binaries and versions, passthrough args, polling, units, theme, web terminal for admins (v2, off by default), remote hosts (v2, read-only; one `name ssh-target` per line)), Protected names, Metrics retention and DB size, Disk cleanup, Notifications (link to Alerts), Access (tokens, users, roles), Audit log, About (version, update check toggle, data dir).

## Actions
Edit settings (admin), add/remove protected names (admin, audited), create/revoke tokens (admin; token shown once), manage users (admin), filter/export audit log (admin), forget metrics history.

## States
- **Empty:** n/a.
- **Loading:** skeleton.
- **Error:** validation errors inline; save failure banner.
- **Unresponsive:** n/a.

## API
`GET/PUT /api/settings`, `GET/POST/DELETE /api/tokens`, `GET/POST/DELETE /api/users`, `GET /api/audit`, `GET /api/disk`, `GET /api/session`.

## Implementation
`SettingsPage` in `web/.../SettingsPage.kt`, tabs General, Protected names, Metrics & disk, Access, Audit log, About. Removing a protected name needs the same typed confirmation as a delete. Disk cleanup runs a dry run, then a typed confirmation. Audit CSV export defuses spreadsheet formulas (`Format.csvCell`).
