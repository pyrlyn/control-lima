# Templates

## Built-in

| Template | Base | Contents |
|---|---|---|
| Docker host | Lima `template://docker` | rootless Docker, socket forwarded to host |
| Colima default | Colima | docker runtime, 2 CPU, 4 GB |
| k3s | Lima `template://k3s` | k3s, kubeconfig copied to host |
| Ubuntu dev | `template://ubuntu-lts` | build-essential, git, mise |
| Alpine minimal | `template://alpine` | smallest footprint |
| x86_64 on Apple Silicon | `template://ubuntu-lts` + `arch: x86_64` | Rosetta (VZ) or QEMU |
| Podman | `template://podman` | podman socket forwarded |

Built-ins come from `limactl create --list-templates` where available, so they follow the installed Lima.

## Custom templates

- Save any existing VM's `lima.yaml` as a template (secrets flagged), or write YAML in the editor (v2 with
  schema validation).
- Parameters: name, CPUs, memory, disk, mounts, port forwards, provision script snippets.
- Stored in `~/.local/share/control-lima/templates/`, included in backups.

## Create VM from template

1. Pick template, fill parameters.
2. Preview: final YAML and the exact command (`limactl create --name=<n> --cpus=... <file>`).
3. Create. The VM is created **stopped**; a "Start now" button is offered, never automatic.
