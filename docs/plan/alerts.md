# Alerts and notifications

## Built-in rules (enabled by default, editable)

| Rule | Condition | Severity |
|---|---|---|
| VM down | was RUNNING, now STOPPED/BROKEN without a user action | critical |
| VM unresponsive | UNRESPONSIVE for ≥ 30 s | critical |
| Guest disk almost full | disk usage ≥ 90 % for 5 min | warning; ≥ 97 % critical |
| Host disk almost full | free space on the `LIMA_HOME` volume < 10 GB | warning |
| Disk full forecast | trend says full in < 3 days | warning |
| High memory | ≥ 95 % for 10 min | warning |
| High CPU | ≥ 90 % for 15 min | info |
| Container restart loop | ≥ 3 restarts in 10 min | warning |
| Container exited unexpectedly | exit code ≠ 0 | warning |

## Custom rules

`{target selector, metric, operator, threshold, for: duration, severity, channels, quiet hours}`.
Selector: one VM, all VMs, all containers, by label/name pattern.

## Lifecycle

pending → firing → (acknowledged) → resolved. De-duplication per rule+target, re-notify every 1 h while
firing (configurable), resolve notification optional. Silence a rule or target for 1 h / 1 d / until
resumed. Events kept 90 days.

## Notifications

- In-app: badge and Alerts page, banner on Overview.
- macOS: `osascript -e 'display notification ...'` through `CommandRunner` (no new deps); Linux:
  `notify-send` if present.
- Webhook: POST JSON to a URL (generic, works with ntfy, Home Assistant, n8n).
- Telegram (optional): bot token + chat id; Slack (optional): incoming webhook URL. Both through the
  Ktor client (approved) behind a `Notifier` interface.
- Secrets for channels are stored in the macOS Keychain via `security` CLI (Linux: file with 0600), never
  in the plain config file and never returned by the API (write-only fields).
- "Send test" per channel.

## Not doing

Auto-remediation (e.g. auto-restart). Alerts never start VMs; they may offer a "Start" button.
