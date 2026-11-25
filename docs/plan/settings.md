# Settings

- Config file `~/.config/control-lima/config.json` (JSON via kotlinx.serialization), editable in the UI;
  CLI flags override it for one launch.
- **Lima homes:** list of `LIMA_HOME` directories (default `~/.lima`); each VM row shows its home.
- **Colima profiles:** auto-discovered from `~/.colima/*`; default profile for containers.
- **Binaries:** paths to `limactl`, `colima`, `docker` socket overrides; version check at start with a
  warning when outside the tested range.
- **Passthrough args** stored per tool.
- **Polling:** live interval (2 s), background interval (15 s), probe timeout (3 s).
- **Protected names** (admin).
- **Metrics retention** and DB size, "forget history" per target.
- **Notifications** channels (see alerts.md).
- **Access:** tokens, users, roles (see auth.md).
- **Appearance:** light/dark/system, density, units (GiB vs GB).
- **Data dir** location, export/import settings.
- **Updates:** check for updates (off by default).
