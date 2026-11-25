# Privacy and telemetry

- No telemetry, no analytics, no crash reporting to third parties. Not even opt-in in v1.
- All data (metrics, logs, audit, backups) stays in the local data dir.
- Network calls only: Docker socket (local), optional notification channels, optional update check.
- The web bundle loads no external fonts, CDNs or scripts.
