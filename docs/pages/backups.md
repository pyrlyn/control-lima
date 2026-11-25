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
