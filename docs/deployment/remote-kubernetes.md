# Remote Kubernetes

Phase 1 does not create a remote cluster. No cloud account, load balancer, certificate, or DNS record is provisioned. The same Helm chart used on kind installs on another Kubernetes distribution when the image values change.

EKS, AKS, GKE, and a cluster on a virtual machine are examples of distributions that can run this chart. This document does not contain provider commands, and it does not mean any of those clusters exists.

## What the cluster must provide

- A Kubernetes API that satisfies the chart constraint `>=1.34.0-0`. The local kind node is 1.37.0.
- Helm 4.3.x, the version used to lint and install the chart.
- Permission to create a namespace, Deployments, Services, and a ConfigMap.
- A way for the nodes to pull from `ghcr.io`.
- Enough memory for seven containers. The chart requests 256Mi for each Java service and 128Mi for the storefront. Limits are 512Mi and 384Mi.

The chart does not install an ingress controller, a certificate manager, or a storage class. Host access on kind is `kubectl port-forward`. A remote cluster can keep that path. Putting a load balancer or ingress in front of the Services is later work and is not part of this chart.

## Images

Build and publish happen in GitHub Actions. The names and tags are in [GitHub Actions and GHCR](github-actions.md). Copy `infrastructure/helm/bazaar/values-remote.example.yaml`, replace the owner and tag, and pass that file to Helm:

```bash
helm upgrade --install bazaar infrastructure/helm/bazaar \
  --namespace bazaar \
  --create-namespace \
  --values infrastructure/helm/bazaar/values-remote.example.yaml
```

`image.registry` is `ghcr.io/<owner>` and `image.tag` is the published tag. `image.pullPolicy` stays `IfNotPresent` for a cluster that already has the image, or becomes `Always` when every start must check the registry. Both are values. Neither is written into the image.

Service DNS names stay `api-gateway`, `identity-service`, `catalog-service`, `inventory-service`, `order-service`, `payment-service`, and `storefront`. The gateway and the storefront read those addresses from ConfigMap `bazaar-config`. A remote cluster does not need laptop host names or node IP addresses.

## Pull secret

A public package needs no pull secret. A private package does. Create the Secret in the cluster. Do not commit the token.

The password is a GitHub personal access token with `read:packages`, created outside the repository. The username is the GitHub user or organization that owns the packages.

```bash
kubectl create namespace bazaar
kubectl --namespace bazaar create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username=<owner> \
  --docker-password=<read:packages token>
```

The example values file sets `imagePullSecrets` to that Secret name. `envFromSecret` stays empty because Phase 1 has no application credential. When a later secret exists, create it in the cluster and set `envFromSecret` to its name. The chart only references the name.

## What stays the same

- One Deployment and one ClusterIP Service per application.
- Readiness, liveness, and Java startup probes.
- CPU and memory requests and limits.
- `terminationGracePeriodSeconds` of 30 for Java and 20 for the storefront.
- Rolling update with `maxSurge: 0`.
- No hostPath, no node IP, and no committed credential.
