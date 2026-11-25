# Access, roles and audit

## Default: single user, per-launch token

- Random 192-bit token generated at launch, printed in the URL. The web app moves it from the URL to
  memory (sessionStorage optional) and sends it as `X-ControlLima-Token`.
- Listens on 127.0.0.1 only. The token protects against other local users and malicious web pages.

## Roles (v1)

| Role | Can |
|---|---|
| viewer | read everything except audit log and secrets |
| operator | viewer + start/stop/restart, container rename, create backups, create VM from template, ack alerts |
| admin | operator + delete, VM rename, restore, prune, settings, alert rules, notification channels, tokens, users, audit log |

Protected names can't be deleted by any role; removing a name from the protected list needs admin and is
audited.

## Named tokens (v1)

- `control-lima token create --name ci --role viewer [--expires 30d]` or Settings & Access page.
- Stored hashed (SHA-256 + per-token salt) in the data dir; shown once.
- Use cases: a second browser, scripts, a read-only wall display.

## Multi-user (optional, v1)

- Off by default. When on: local accounts (username + password hashed with PBKDF2 from the JDK), session
  cookie (`HttpOnly`, `SameSite=Strict`), roles per user.
- No external IdP in v1. OIDC is a v2 idea and would need approval for a dependency.

## Audit log

- Every write action, login, token use from a new client, failed auth, setting change.
- Record: time, actor (user/token name), role, action, target, parameters (secrets redacted), result,
  duration.
- JSON lines in `~/.local/share/control-lima/audit.log`, rotated at 10 MB, kept 1 year; Audit view in
  Settings & Access with filters and export.
