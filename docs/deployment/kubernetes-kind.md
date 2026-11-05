# kind and Helm

kind runs the Phase 1 Helm chart on one local node. The chart is the Kubernetes deployment. Compose is not converted into manifests. A remote cluster uses the same chart with different values. Phase 1 does not create that cluster.

Run these commands in WSL Ubuntu. kind 0.33.0, Helm 4.3.1, and kubectl 1.37.2 are installed there. Windows kubectl from Docker Desktop is 1.36.1 and does not match this cluster.

Stop Docker Compose first. The Docker Desktop VM has about 7.6 GiB, and the kind node plus seven application containers need that memory.

## Create, load, and install

From the repository root inside WSL:

```bash
bash infrastructure/scripts/create-cluster.sh
bash infrastructure/scripts/load-images.sh
bash infrastructure/scripts/install.sh
```

`create-cluster.sh` refuses to start when Compose containers are running or when a cluster named `bazaar` already exists. The node image is pinned in `infrastructure/kind/cluster.yaml`.

`load-images.sh` copies the seven local images into kind. Those images come from `docker compose build` and are tagged `:latest`. The chart pull policy is `IfNotPresent`, so the node uses the loaded images.

`install.sh` installs release `bazaar` into namespace `bazaar` and waits until the Deployments are ready.

## What the chart creates

Each application has one Deployment and one ClusterIP Service. Service names match the Compose DNS names, so the gateway calls `http://identity-service:8080` and the other backends the same way. The storefront calls `http://api-gateway:8080`.

Addresses live in ConfigMap `bazaar-config`. Pods read the keys they need. The chart does not create a Secret. `imagePullSecrets` and `envFromSecret` name Secrets that an operator creates in the namespace. Phase 1 commits no credentials.

Java pods probe `/actuator/health/liveness` for startup and liveness, and `/actuator/health/readiness` for readiness. The storefront probes `/` for liveness and readiness, and that probe does not call the gateway. Java containers set `MaxRAMPercentage=75.0` so the heap stays inside the 512Mi limit. `terminationGracePeriodSeconds` is 30 for Java, which covers the 20 second Spring shutdown phase. Rolling updates use `maxSurge: 0` so a rollout does not start a second copy of every JVM on this machine.

There is no ingress, host port, hostPath, or node IP.

## Open the applications

In two WSL terminals:

```bash
kubectl --namespace bazaar port-forward svc/storefront 3000:3000
kubectl --namespace bazaar port-forward svc/api-gateway 8080:8080
```

Open http://localhost:3000 and http://localhost:8080/actuator/health. Stop the port-forwards with Ctrl+C. They are not a substitute for an ingress controller.

## Upgrade and rollout

`upgrade.sh` updates the existing release and waits. A changed ConfigMap rolls the pods because the pod template carries a checksum of that ConfigMap. To roll without any other change:

```bash
bash infrastructure/scripts/upgrade.sh --set revision=2
kubectl --namespace bazaar rollout status deployment/storefront
```

## Delete

```bash
bash infrastructure/scripts/delete-cluster.sh
```

This removes the kind cluster, the namespace, and the loaded images inside the node. Local Docker images remain.

## Remote values

`infrastructure/helm/bazaar/values-remote.example.yaml` points the same chart at `ghcr.io/<owner>/bazaar-<application>:<tag>` and names a pull secret. Replace the owner and tag. Create the Secret in the cluster. Do not commit the Secret. Phase 1 does not provision a cloud cluster, load balancer, certificate, or DNS name.
