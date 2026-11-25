# CLI and TUI (v2)

- `control-lima` (default): start the server.
- `control-lima status [--json]`: VMs and containers summary through the same providers.
- `control-lima token create|list|revoke`.
- `control-lima backup [vm...]`, `control-lima restore <id>`.
- `control-lima top`: terminal dashboard of live load (TUI). A TUI library would be a new dependency;
  first version uses plain ANSI output.
- Exit codes: 0 ok, 1 error, 2 usage, 3 provider unavailable.
