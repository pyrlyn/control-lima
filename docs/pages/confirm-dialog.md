# Confirm destructive action dialog

Figma: https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-620 (dark), https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-2651 (light)

## Purpose
Shared dialog for delete, restore, prune and VM rename.

## Data shown
What will happen (target, source, size to be freed, data lost), a text field "Type <name> to confirm", protected-name notice.

## Actions
Confirm (enabled only when the typed text equals the name exactly), cancel (Esc).

## States
- **Empty:** n/a.
- **Loading:** confirm button spinner while the request runs.
- **Error:** server error shown inline (e.g. 409 protected, 422 name mismatch).
- **Unresponsive:** for an unresponsive VM, warns that delete may need force stop first.

## API
Whatever the calling action uses; the body always carries `confirmName`.

## Implementation
`ConfirmDialog` in `web/.../Ui.kt`, shared by every destructive action. Confirm stays disabled until the typed text matches exactly (`Confirm.matches` from `shared/`); protected names show a blocking banner instead of the input. Esc closes the dialog.
