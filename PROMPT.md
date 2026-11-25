# ControlLima: implementation prompt

You are implementing ControlLima, a local control panel and dashboard for Lima and Colima, in this repository.

## 1. Read first
Before writing any code, read in full:
- `README.md` and `AGENTS.md`
- every file under `docs/plan/` and `docs/` (architecture, API, metrics, alerts, auth, backup/restore, templates, logs, images, ports/network, security, risks)
- every page description under `docs/pages/`

The plan files are the specification. If code and plan disagree, follow the plan and update it in the same change only when the plan is clearly wrong; note every such change in your final report.

## 2. Implement the full plan
Implement everything in the plan, phase by phase (MVP first, then later phases), keeping the build green after each step:
- **Backend:** Ktor (JVM) server behind the project's own `HttpServer` interface. Providers for `limactl`, `colima` and the Docker Engine API over the Colima socket, each behind its own interface. Use JSON output and the Docker API only; never parse human-readable CLI text. Every external call has a timeout, and an unresponsive VM is reported as its own state.
- **Frontend:** Compose Multiplatform for Web (`wasmJs`), sharing models and the API client from `shared/` (kotlinx.serialization).
- **Inventory:** VM and container lists, ports and port forwards, IP addresses, images (list, size, tags, usage).
- **Real-time load:** per-VM and per-container CPU, memory, network and disk over WebSocket.
- **Actions:** start, stop and delete for VMs and containers, and container rename. Destructive actions need confirmation by typing the name. Protected names (for example `infra-test`) can never be deleted. The app never starts a VM on its own.
- **Charts:** CPU, memory, network and disk per VM over time.
- **History:** stored metrics with downsampling and retention, plus trends.
- **Alerts:** thresholds, VM down or unresponsive, disk almost full; notifications as described in the plan.
- **Access:** localhost only by default, token-based access with roles (viewer, operator, admin), Origin and CSRF checks, audit log.
- **Backup and restore** of VM configs (`lima.yaml`, Colima profiles).
- **Templates** for common VM setups.
- **Logs viewer:** container logs, Lima host-agent and serial logs, guest journal.
- Anything else the plan lists.

## 3. Conventions
- Follow `AGENTS.md`. For every page or screen you add or change, create or update `docs/pages/<page>.md` in the same change: purpose, data shown, actions, states (empty, loading, error, unresponsive), API endpoints, and a `Figma:` link to the exact frame or page.
- Keep the Figma file placeholder (`Figma file: TBD`) in `README.md` and `AGENTS.md`, and `Figma: TBD` in page docs, until a real Figma file exists. Never invent Figma URLs.
- Every third-party library sits behind our own interface. Approved: Ktor, Compose Multiplatform, kotlinx.* and the Kotlin stdlib. Any other dependency needs approval: list it in your report instead of adding it.
- A module's tests cover only that module. App-level tests live in the app. Provider tests use recorded JSON fixtures and never start real VMs.
- GitHub Actions workflows are generated from `ci.main.kts`, never hand-written YAML.
- All code, comments and docs are in English.

## 4. Delivery
- Do not push, do not open a pull request, and do not change git remotes. Leave the work committed locally in small, logical commits (or in the working tree if asked).
- Before finishing, run the full build and all tests and make sure they pass.
- Final report: what was implemented per plan section, what is not done and why, test and build commands with results, any plan changes, and dependencies awaiting approval.
