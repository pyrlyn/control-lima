# Ports and network

## Data

- Lima port forwards from each VM config (`portForwards`) and the effective forwards from the host agent
  log ("Forwarding TCP from ...").
- SSH local port per VM.
- VM IP addresses (`network[].ipAddress` in `limactl list --json`; guest `ip -j addr` over SSH for v1).
- Colima address (`colima list --json`).
- Container ports (`Ports` in Docker API) and container IPs per network.
- Host listeners: `lsof -nP -iTCP -sTCP:LISTEN -F pcn` (machine-readable field output, not the table),
  mapped to owning process (limactl hostagent, ssh, docker proxy, other).

## Views

- One table: host IP:port → guest/container port, protocol, owner (VM/container/process), state.
- Conflicts: two forwards on the same host port, a forward shadowed by another process, privileged ports.
- Quick actions: open `http://127.0.0.1:<port>` in a new tab, copy, jump to VM/container.
- Networks (v1): Lima networks (`$LIMA_HOME/_config/networks.yaml`), Docker networks.

## Risks

`lsof` can be slow; run with 5 s timeout, cache 10 s.
