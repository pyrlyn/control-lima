# Kubernetes

Figma: TBD

## Purpose
Read-mostly view of the Kubernetes clusters that run inside Colima (`colima start --kubernetes`) and Lima
VMs made from the k3s/k8s templates (docs/plan/kubernetes.md).

## Data shown
Cluster switcher (when there are several). Nodes: ready, roles, kubelet version, IP, CPU and memory
(usage / capacity). Pods, filtered by namespace chips: namespace, name, status as `kubectl get pods` shows
it, ready containers, restarts, node, CPU and memory. Services: type, cluster IP, ports and node ports.

## Actions
- Logs of a pod (opens the Logs page on that pod; one source per container).
- Delete pod (admin, type the pod name). Its controller starts a new one, which is how a pod is restarted;
  a bare pod is gone for good, and the dialog says so.
- Port-forward is planned for v2.1 and not offered yet.

## States
- **Empty:** no cluster found → how to enable Kubernetes in Colima or Lima.
- **Loading:** skeleton.
- **VM stopped:** the cluster is listed with "<vm> is not running" and never contacted.
- **Error:** kubeconfig unreadable, TLS or API failure → banner with the API's message and Retry.
- **No metrics-server:** a note; CPU and memory columns stay empty.

## API
`GET /api/kube/clusters`, `GET /api/kube/clusters/{source}/{vm}`,
`DELETE /api/kube/clusters/{source}/{vm}/pods/{namespace}/{pod}` (body `{confirmName}`),
`GET /api/logs/sources?target=kube:<source>/<vm>/<namespace>/<pod>`.

## Implementation
`KubernetesPage` in `web/.../KubePage.kt`; `KubernetesService` and `ApiKube.kt` on the server;
`providers/kube` for kubeconfig parsing and the HTTPS client. Clusters are found from files only:
`~/.kube/config` contexts `colima` / `colima-<profile>` for Colima profiles whose runtime contains `k3s`,
and `<instance>/copied-from-guest/kubeconfig.yaml` for Lima VMs. Exec and auth-provider users and
`insecure-skip-tls-verify` are refused. Pod logs are a tail (up to 10 000 lines); live follow is not
implemented yet, so the Logs page shows the tail and stops. Node-port services also appear on the Ports
page as "k8s service" rows on 127.0.0.1, because Lima forwards them to the host.
