# Kubernetes

Figma: TBD

## Purpose
Read-mostly view of the Kubernetes clusters that run inside Colima (`colima start --kubernetes`) and Lima
VMs made from the k3s/k8s templates (docs/plan/kubernetes.md).

## Data shown
Cluster switcher (when there are several). Nodes: ready, roles, kubelet version, IP, CPU and memory
(usage / capacity). Pods, filtered by namespace chips: namespace, name, status as `kubectl get pods` shows
it, ready containers, restarts, node, CPU and memory. Services: type, cluster IP, ports and node ports. Port forwards of the cluster: local address, target,
open and total connections, bytes each way, the last connection error.

## Actions
- Logs of a pod (opens the Logs page on that pod; one source per container).
- Delete pod (admin, type the pod name). Its controller starts a new one, which is how a pod is restarted;
  a bare pod is gone for good, and the dialog says so.
- Forward a pod or a service (operator): pick the port (the declared TCP ports are offered) and a local port
  (default: the same number when it is 1024 or above; empty: any free port). The forward listens on
  127.0.0.1 only and runs until it is stopped or ControlLima exits. A service forward goes to a running,
  ready pod behind its selector and the service port's target port, picked again for every connection.
- Open, copy or stop a forward. Stopping closes its open connections.

## States
- **Empty:** no cluster found → how to enable Kubernetes in Colima or Lima.
- **Loading:** skeleton.
- **VM stopped:** the cluster is listed with "<vm> is not running" and never contacted.
- **Error:** kubeconfig unreadable, TLS or API failure → banner with the API's message and Retry.
- **No metrics-server:** a note; CPU and memory columns stay empty.
- **Forward failing:** the row is highlighted with the last error (pod gone, port refused); the listener stays.
- **Local port busy:** the dialog shows "127.0.0.1:<port> is already in use".

## API
`GET /api/kube/clusters`, `GET /api/kube/clusters/{source}/{vm}`,
`DELETE /api/kube/clusters/{source}/{vm}/pods/{namespace}/{pod}` (body `{confirmName}`),
`GET /api/logs/sources?target=kube:<source>/<vm>/<namespace>/<pod>`, `GET /api/kube/forwards`,
`POST /api/kube/clusters/{source}/{vm}/forwards`, `DELETE /api/kube/forwards/{id}`.

## Implementation
`KubernetesPage` in `web/.../KubePage.kt`; `KubernetesService` and `ApiKube.kt` on the server;
`providers/kube` for kubeconfig parsing and the HTTPS client. Clusters are found from files only:
`~/.kube/config` contexts `colima` / `colima-<profile>` for Colima profiles whose runtime contains `k3s`,
and `<instance>/copied-from-guest/kubeconfig.yaml` for Lima VMs. Exec and auth-provider users and
`insecure-skip-tls-verify` are refused. Pod logs are a tail (up to 10 000 lines); live follow is not
implemented yet, so the Logs page shows the tail and stops. Node-port services also appear on the Ports
page as "k8s service" rows on 127.0.0.1, because Lima forwards them to the host.

Port forwards (v2.1): `PortForwardService` on the server owns the listeners; `KubernetesService` checks the
target when a forward starts and opens one stream per connection through `KubeClient.portForward`, the
`pods/{pod}/portforward?ports=<port>` WebSocket with the `v4.channel.k8s.io` subprotocol
(`PortForwardFrames` in `providers/kube`). That protocol has no half-close, so either side ending ends the
connection, as with kubectl. Limits: 32 forwards, 64 open connections each. They appear on the Ports page as
"k8s forward" rows.
