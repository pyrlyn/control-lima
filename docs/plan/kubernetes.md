# Kubernetes in Colima (v2)

- Detect `colima start --kubernetes` profiles (`runtime` contains `k3s`) and Lima k3s/k8s templates.
- Read kubeconfig from the profile; talk to the API server through a `KubeClient` interface over the
  Ktor client (approved) — no kubectl parsing, no new k8s client library without approval.
- Views: nodes, namespaces, pods (status, restarts, CPU/memory from metrics-server if present), services
  and their node ports in the Ports page, pod logs in the Logs page.
- Actions: delete pod (restart), port-forward (v2.1).
