# Risks

| Risk | Impact | Mitigation |
|---|---|---|
| CLI output changes between Lima/Colima versions | broken lists | JSON only, `ignoreUnknownKeys`, fixtures per version, version check at start |
| VM shows Running but is dead; `limactl shell` hangs | UI hangs | timeouts everywhere, background probes, `UNRESPONSIVE` state, host agent log check |
| No guest stats API in Lima | no real load data | persistent SSH session reading `/proc`; host process stats as fallback |
| SSH sessions leak or pile up | resource drain | one session per VM, reconnect with backoff, close when VM stops |
| Docker socket missing when Colima stopped | errors | provider reports "unavailable", UI shows empty state with reason |
| Accidental deletion | data loss | type-to-confirm, protected names, admin role, audit, backups |
| Malicious web page hits localhost API | VM deletion | token, Host/Origin checks, CSRF, no CORS |
| VM rename unsupported by Lima | corrupted VM | v1 only for stopped VMs via documented path; test on fixtures and a throwaway VM; otherwise offer clone + delete |
| Metrics DB growth | disk use | downsampling, retention, size shown, forget history |
| SQLite needs a new dependency | blocked | ask for approval; fallback file store |
| Compose for Web (wasm) maturity: bundle size, accessibility, browser support | slow first load, a11y gaps | lazy pages, production build, track upstream; kotlinx.html fallback considered |
| Long actions (start, pull) block requests | stuck UI | 202 + progress over `/ws/events` |
| Multiple `LIMA_HOME` and profiles | wrong target | VM identity = (source, home, name) |
| macOS notification via `osascript` blocked by permissions | silent alerts | in-app alerts always on; test button |
