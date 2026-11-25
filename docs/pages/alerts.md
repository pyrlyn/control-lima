# Alerts

Figma: TBD

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
