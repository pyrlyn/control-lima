# Alerts

Figma: alerts https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-1662 (dark), https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-3693 (light); rules https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-1797 (dark), https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima?node-id=1-3828 (light)

## Purpose
See firing and past alerts, manage rules and notification channels.

## Data shown
Tabs: Firing (severity, rule, target, since, ack state), History (90 days), Rules (built-in and custom, enabled, last fired), Channels (desktop, webhook, Telegram, Slack; status of last send).

## Actions
Acknowledge (operator), silence (operator), create/edit/delete rule (admin), configure channel and send test (admin), "Start VM" button on a VM-down alert (explicit).

## States
- **Empty:** "All good, no alerts".
- **Loading:** skeleton.
- **Error:** alert engine error banner.
- **Unresponsive:** unresponsive VMs appear as critical firing alerts.

## API
`GET/POST/PUT/DELETE /api/alerts/rules`, `GET /api/alerts/events`, `POST /api/alerts/{id}/ack`, `GET/PUT /api/notifications/channels`, `POST /api/notifications/test`, `WS /ws/events`.

## Implementation
`AlertsPage` in `web/.../OpsPages.kt`, tabs Firing, History, Rules, Channels. Silence uses `POST /api/alerts/rules/{id}/silence`. A VM-down alert offers Start VM, which runs only on that click.
