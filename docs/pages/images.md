# Images

Figma: TBD

## Purpose
See images, their size and who uses them; clean up (v1).

## Data shown
Table: repository:tag(s), ID, size, created, used by (containers count, links), dangling. Totals and reclaimable size.

## Actions
Pull (operator, v1), delete (admin, v1, refuses in-use), prune with dry-run preview (admin, v1), view layers.

## States
- **Empty:** "No images".
- **Loading:** skeleton.
- **Error:** Docker socket missing / API error.
- **Unresponsive:** API timeout banner.

## API
`GET /api/images?profile=`, `DELETE /api/images/{id}`, `POST /api/images/prune`, `POST /api/images/pull`, `GET /api/disk`.
