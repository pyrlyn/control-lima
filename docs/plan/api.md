# API

All endpoints are on `127.0.0.1:<port>`. JSON bodies use the models in `shared`. Errors are
`{"code": "...", "message": "..."}` with a proper HTTP status (400, 401, 403, 404, 409, 422, 502, 504).

## Auth

- Every `/api/*` and `/ws/*` call needs the token: header `X-ControlLima-Token`, or `?token=` on the first
  page load (the web app then keeps it in memory and strips it from the URL).
- `Origin`, when present, must be `http://127.0.0.1:<port>` or `http://localhost:<port>`.
- Writes (`POST`, `PUT`, `PATCH`, `DELETE`) also need `X-CSRF-Token` from `GET /api/session`.
- Role per endpoint: **V** viewer, **O** operator, **A** admin (see [auth.md](auth.md)).

## Endpoints

| Method | Path | Role | Phase | Purpose |
|---|---|---|---|---|
| GET | `/health` | public | MVP | `{status, version}` |
| GET | `/api/session` | V | MVP | user, role, CSRF token, protected names |
| GET | `/api/vms` | V | MVP | all Lima and Colima VMs |
| GET | `/api/vms/{source}/{name}` | V | MVP | one VM with config summary, forwards, IPs |
| POST | `/api/vms/{source}/{name}/start` | O | MVP | start (explicit user action only) |
| POST | `/api/vms/{source}/{name}/stop` | O | MVP | stop (`?force=true` A) |
| DELETE | `/api/vms/{source}/{name}` | A | MVP | body `{"confirmName": "<name>"}`; 409 if protected |
| POST | `/api/vms/{source}/{name}/rename` | A | v1 | body `{newName, confirmName}`; stopped VMs only |
| POST | `/api/vms/{source}/{name}/clone` | A | v1 | where supported |
| GET | `/api/containers?profile=` | V | MVP | containers of a Colima profile |
| GET | `/api/containers/{id}` | V | MVP | inspect summary |
| POST | `/api/containers/{id}/start` \| `/stop` \| `/restart` | O | MVP | |
| POST | `/api/containers/{id}/rename` | O | MVP | `{newName}` |
| DELETE | `/api/containers/{id}` | A | MVP | `{confirmName}` |
| GET | `/api/images?profile=` | V | MVP | images with size, tags, used-by |
| DELETE | `/api/images/{id}` | A | v1 | refuses images in use |
| POST | `/api/images/prune` | A | v1 | `?dryRun=true` first |
| POST | `/api/images/pull` | O | v1 | `{ref}`, streamed progress |
| GET | `/api/ports` | V | MVP | forwards, container ports, host listeners, conflicts |
| GET | `/api/metrics?target=&metric=&from=&to=&step=` | V | v1 | history series |
| GET | `/api/metrics/trends?target=` | V | v1 | disk growth, forecast |
| GET/POST/PUT/DELETE | `/api/alerts/rules[/{id}]` | V/A | v1 | alert rules |
| GET | `/api/alerts/events` | V | v1 | fired/resolved events |
| POST | `/api/alerts/{id}/ack` | O | v1 | acknowledge |
| GET/PUT | `/api/notifications/channels` | A | v1 | desktop, webhook, Telegram, Slack |
| POST | `/api/notifications/test` | A | v1 | send a test notification |
| GET | `/api/logs/sources?target=` | V | v1 | available log sources |
| GET | `/api/backups` | V | v1 | list backups |
| POST | `/api/backups` | O | v1 | back up selected VM configs |
| GET | `/api/backups/{id}/diff?target=` | V | v1 | diff vs current |
| POST | `/api/backups/{id}/restore` | A | v1 | `{targets, confirmName}` |
| GET | `/api/backups/{id}/download` | A | v1 | tar.gz |
| GET/POST/PUT/DELETE | `/api/templates[/{id}]` | V/A | v1 | templates |
| POST | `/api/templates/{id}/create-vm` | O | v1 | `{name, overrides}`; returns preview first (`?dryRun=true`) |
| GET | `/api/disk` | V | v1 | disk usage per VM, images, build cache |
| GET/PUT | `/api/settings` | V/A | v1 | settings |
| GET/POST/DELETE | `/api/tokens[/{id}]` | A | v1 | named tokens with roles |
| GET/POST/DELETE | `/api/users[/{id}]` | A | v1 | optional multi-user |
| GET | `/api/audit?from=&to=&actor=` | A | v1 | audit log |
| GET | `/api/vms/{source}/{name}/snapshots` | V | v2 | `{supported, reason, tags}`; QEMU Lima VMs only |
| POST | `/api/vms/{source}/{name}/snapshots` | O | v2 | `{tag}`; action |
| POST | `/api/vms/{source}/{name}/snapshots/{tag}/apply` | A | v2 | `{confirmName: <vm>}`; action |
| DELETE | `/api/vms/{source}/{name}/snapshots/{tag}` | A | v2 | `{confirmName: <tag>}`; action |
| GET | `/api/vms/{source}/{name}/config` | A | v2 | unredacted `lima.yaml` and whether it can be edited now |
| PUT | `/api/vms/{source}/{name}/config` | A | v2 | `{yaml, confirmName}`; stopped Lima VMs; validates, backs up, then writes |
| POST | `/api/lima/validate` | O | v2 | `{yaml}` → `limactl validate` messages |

## WebSocket

- `/ws/stats?targets=vm:lima/dev,container:<id>`: server pushes `StatsSample` every 2 s per target.
  Client may send `{"subscribe": [...]}` / `{"unsubscribe": [...]}`.
- `/ws/events`: VM/container state changes, alert events, long-running action progress.
- `/ws/logs?source=...&follow=true&since=`: log lines `{ts, stream, line}`.

## Long-running actions

Start, stop, pull, restore and create-from-template return `202 {"actionId"}` and report progress on
`/ws/events`. `GET /api/actions/{id}` returns the final state.

## Versioning

Path is unversioned while the server and web ship together; `/health` carries the version and the web
app refuses to run against a different server version.
