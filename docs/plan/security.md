# Security

## Threat model

ControlLima can delete VMs and containers, so the main threats are:

1. A malicious web page in the user's browser calling `http://127.0.0.1:8787` (CSRF, DNS rebinding).
2. Another local user or process on the same machine.
3. Accidental destruction by the user.
4. Leaking secrets (notification tokens, env vars in container inspect, cloud-init data in `lima.yaml`).

## Controls

- **Bind 127.0.0.1 only.** `ServerConfig` refuses other hosts. Remote access is not supported; users who
  want it should use an SSH tunnel. (A later `--listen` flag would need TLS + multi-user auth first.)
- **Token** on every `/api` and `/ws` request, compared in constant time.
- **Host header check** (`127.0.0.1:<port>` or `localhost:<port>`) against DNS rebinding.
- **Origin check** for all requests with an Origin header, including WebSocket upgrades.
- **CSRF token** on writes in addition to the custom header; no CORS headers at all.
- **Security headers:** `Content-Security-Policy: default-src 'self'; script-src 'self' 'wasm-unsafe-eval'`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on API.
- **Confirm by typing the name** for delete, restore, prune, VM rename.
- **Protected names** (default `infra-test`) can never be deleted; checked on the server, not only in UI.
- **No automatic VM start**, ever; alerts and templates only offer buttons.
- **No shell injection:** commands are argument lists, VM names validated against Lima's name regex
  (`^[a-zA-Z0-9][a-zA-Z0-9_.-]*$`) before use.
- **Secrets**: redacted in API responses (env vars in container inspect shown as `KEY=•••` unless admin
  reveals one, audited), notification secrets in Keychain, never logged.
- **Data dir permissions** 0700, files 0600.
- **Dependencies**: minimal and approved; versions pinned in the catalog; Renovate/Dependabot later.
- **Audit log** for all writes.

## Out of scope

Protecting against a malicious process running as the same user (it can call `limactl` directly).
