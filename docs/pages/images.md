# Images

Figma: https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-921 (dark), https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-2952 (light)

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

## Implementation
`ImagesPage` in `web/.../DockerPages.kt`. Pull validates the reference with `Names.isValidImageRef` (shared with the server). Prune runs as a dry run first and then needs the typed word `prune`.
