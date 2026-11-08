# kind and Helm

kind runs the Helm chart on one local node. The chart deploys the applications. It does not deploy PostgreSQL, Kafka, Redis, or Keycloak, and it does not invent their addresses. Compose remains the place where those stores run for local development. Do not run Compose and kind at the same time. The Docker Desktop VM has about 7.6 GiB.

Run these commands in WSL Ubuntu. kind 0.33.0, Helm 4.3.1, and kubectl 1.37.2 are installed there. Windows kubectl from Docker Desktop is 1.36.1 and does not match this cluster.

## Local development, local kind, and a remote cluster

| Target | What runs the applications | What runs the data stores |
| --- | --- | --- |
| Local processes | Host JDK and Node.js | Docker Compose, or processes named in the service configuration |
| Docker Compose | Compose on one Docker network | Postgres, Kafka, Redis, and Keycloak in the same Compose file |
| Local kind | This Helm chart, images loaded with `kind load` | Not in the cluster. Readiness stays false until an operator supplies them |
| Remote Kubernetes | The same chart with `values-remote.example.yaml` | The same rule. Create Secrets in the cluster. Do not commit them |

`install.sh` waits up to 8 minutes. That wait fails on a laptop kind cluster because the Java services have no database. The chart is still installed. Use `helm upgrade --install` without `--wait` for that check, then read the pod conditions. Do not remove the readiness probes to make the wait pass.

The Java images default to `localhost:5432`. Without `envFromSecret`, Flyway cannot connect, the process exits, and the startup probe reports connection refused. Notification without `DATABASE_URL` uses its in-memory store, so `/health/ready` returns 200. Set the secret before treating that probe as a database check.

## Create, load, and install

Stop Compose without deleting volumes, from the repository root:

```bash
docker compose stop
```

Then, from the repository root inside WSL:

```bash
bash infrastructure/scripts/create-cluster.sh
bash infrastructure/scripts/load-images.sh
helm upgrade --install bazaar infrastructure/helm/bazaar \
  --namespace bazaar \
  --create-namespace
```

`create-cluster.sh` refuses to start when Compose containers are running or when a cluster named `bazaar` already exists. The node image is pinned in `infrastructure/kind/cluster.yaml`.

`load-images.sh` copies the local images into kind, including `bazaar-notification-service:latest`. The chart pull policy is `IfNotPresent`.

After the check, delete the cluster and start Compose again:

```bash
bash infrastructure/scripts/delete-cluster.sh
docker compose start
```

`docker compose down -v` deletes the database volume. Do not use it to make room for kind.

## What the chart creates

Each application has one Deployment and one ClusterIP Service: API Gateway, Identity, Catalog, Inventory, Order, Payment, Notification, and Storefront. Service names match the Compose DNS names.

Addresses that are not secrets live in ConfigMap `bazaar-config`. Pods read the keys they need. The chart does not create a Secret. `imagePullSecrets` and `envFromSecret` name Secrets that an operator creates in the namespace. Database URLs and passwords stay in that Secret.

Java pods probe `/actuator/health/liveness` for startup and liveness, and `/actuator/health/readiness` for readiness. Readiness includes the database and does not include Kafka, Redis, or Prometheus. The storefront probes `/` and does not call the gateway. Notification probes `/health` for startup and liveness, and `/health/ready` for readiness. `/health/ready` checks its database.

Java containers set `MaxRAMPercentage=75.0` so the heap stays inside the 512Mi limit. `terminationGracePeriodSeconds` is 30 for Java and 20 for Node. Each container runs `sleep 5` in `preStop` so endpoints can leave the Service before the process stops. Spring still has a 20 second shutdown phase, and that fits inside the 30 second grace period. Rolling updates use `maxUnavailable: 1` and `maxSurge: 0`.

The local values create one HorizontalPodAutoscaler per Deployment. The minimum and the maximum are both 1, so the laptop does not start a second JVM. Gateway and storefront are allowed to follow CPU at 70 percent. Identity, Catalog, Inventory, Order, Payment, and Notification set `scale.cpu` to false, which disables scale-up and scale-down. Catalog, Inventory, Order, Payment, and Notification consume Kafka. The topics have 3 partitions, so more than 3 replicas do not share more work. Each database-backed Java pod opens at most 10 Hikari connections. Six services at two replicas would ask for 120 connections, which is above a default Postgres limit of 100.

A PodDisruptionBudget is rendered only when a service minimum is at least 2. One replica with `minAvailable: 1` would block draining the only kind node. Local values stay at one replica, so no budget is created.

Ingress is a template and it is disabled. The chart does not install an ingress controller. There is no host port, hostPath, or node IP. Local kind has no metrics-server, so the autoscalers report an unknown CPU metric and stay at one replica.

## Open the applications

In two WSL terminals:

```bash
kubectl --namespace bazaar port-forward svc/storefront 3000:3000
kubectl --namespace bazaar port-forward svc/api-gateway 8080:8080
```

Open http://localhost:3000 and http://localhost:8080/actuator/health. Stop the port-forwards with Ctrl+C. Checkout and notifications still need the external stores.

## Upgrade and rollout

`upgrade.sh` updates the existing release and waits. A changed ConfigMap rolls the pods because the pod template carries a checksum of that ConfigMap. To roll without any other change:

```bash
bash infrastructure/scripts/upgrade.sh --set revision=2
kubectl --namespace bazaar rollout status deployment/storefront
```

On this laptop the wait in `upgrade.sh` has the same limit as `install.sh`: Java pods stay unready until Postgres and Keycloak are reachable.

## Delete

```bash
bash infrastructure/scripts/delete-cluster.sh
```

This removes the kind cluster, the namespace, and the loaded images inside the node. Local Docker images remain. Start Compose again with `docker compose start` if it was stopped for the cluster.

## Local check

On 8 November 2026 the chart was installed on kind after `docker compose stop`. Helm lint reported 0 failures. The rendered local chart had 8 Deployments, 8 Services, 8 autoscalers, 1 ConfigMap, no PodDisruptionBudget, and no Ingress. The remote example rendered 2 disruption budgets and no Ingress.

| Pod | Ready | What was observed |
| --- | --- | --- |
| storefront | 1/1 | `GET /` returned 200 |
| api-gateway | 1/1 | `GET /actuator/health/readiness` returned 200 |
| notification-service | 1/1 | `GET /health/ready` returned 200 with no `DATABASE_URL`, so the in-memory store was in use |
| identity, catalog, inventory, order, payment | 0/1 | Startup failed. Order logged Flyway `08001`, connection to `localhost:5432` refused, then the kubelet backed off |

Every autoscaler showed `cpu: <unknown>/70%` because this kind cluster has no metrics-server. Replicas stayed at 1. Order scale-up and scale-down were `Disabled`. The gateway autoscaler had no such policy and its maximum was 1. The cluster was deleted afterward and Compose was started again.

## Remote values

`infrastructure/helm/bazaar/values-remote.example.yaml` points the same chart at `ghcr.io/<owner>/bazaar-<application>:<tag>`, names a pull secret, raises the gateway and storefront replica range, and leaves ingress disabled with an example host. Replace the owner and tag. Create Secrets in the cluster. Do not commit a token or a database password. This repository does not provision a cloud cluster, load balancer, certificate, or DNS name.
