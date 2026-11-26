# Backups

Figma: TBD

## Purpose
Back up and restore VM configs and ControlLima settings.

## Data shown
Backup list (time, scope, size, manual/scheduled, flagged secrets), schedule settings, restore wizard with diff.

## Actions
Back up now (operator), schedule (admin), download/upload archive (admin), restore with diff and typed confirmation (admin), delete backup (admin).

## States
- **Empty:** "No backups yet" + Back up now.
- **Loading:** skeleton.
- **Error:** write/read failure banner.
- **Unresponsive:** restore to a running/unresponsive VM shows "restart required, not done automatically".

## API
`GET/POST /api/backups`, `GET /api/backups/{id}/diff`, `POST /api/backups/{id}/restore`, `GET /api/backups/{id}/download`.

## Implementation
`BackupsPage` in `web/.../ManagePages.kt`. Back up now scans for secrets first (`POST /api/backups/scan`) and needs a second click if any are found and redaction is off. Upload uses `POST /api/backups/import`; download sends the token header, so it goes through an authorized request rather than a plain link. The schedule is saved with `PUT /api/settings`. A restore of several targets confirms with the backup id; a protected VM can only be restored under a new name.
