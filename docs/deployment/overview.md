# Deployment overview

Phase 1 has three deployment targets. They share images and environment variable names. They do not share a single deployment file. The Phase 2 purchase path is verified with Docker Compose. kind stays without Postgres and Keycloak.

| Target | When it appears | Purpose |
| --- | --- | --- |
| Local processes | M2 and M3 | Fast edit and test on the host JDK and Node.js |
| Docker Compose | M4 | Run the packaged images on one Docker network |
| kind | M5 | Run the Helm chart on local Kubernetes |
| Remote Kubernetes | Documented in M6, not provisioned | Same chart, different image values |

Compose steps are in [docker-compose.md](docker-compose.md). kind steps are in [kubernetes-kind.md](kubernetes-kind.md). Remote Kubernetes steps are in [remote-kubernetes.md](remote-kubernetes.md). The design they follow is [deployment architecture](../architecture/deployment-architecture.md). The decision record is [ADR-004](../adr/ADR-004-docker-and-kubernetes.md).

## What moves between environments

Images move. Configuration stays outside the image.

- Local processes read `.env` or the shell.
- Compose sets the same variables to Docker DNS names.
- Kubernetes puts non-sensitive URLs in a ConfigMap and passes them as environment variables.
- Sensitive values, when they exist, are Secret references. Phase 1 has none to commit.

## Host access

| Environment | Storefront | Gateway |
| --- | --- | --- |
| Local process | `http://localhost:3000` | `http://localhost:8080` |
| Compose | `http://localhost:3000` | `http://localhost:8080` |
| kind | `kubectl port-forward` to port 3000 | `kubectl port-forward` to port 8080 |

Backend services stay on the Compose network or on ClusterIP Services. Their host ports in Compose are for direct health checks. Kubernetes does not publish them on the node.

## Cluster toolchain

Create and install the local cluster from WSL Ubuntu:

- kind 0.33.0
- kubectl 1.37.2
- Helm 4.3.1
- Kubernetes 1.37.0 node image pinned in [ADR-004](../adr/ADR-004-docker-and-kubernetes.md)

Docker Desktop is the runtime for both Windows builds and WSL kind.

## Remote clusters

A remote cluster on EKS, AKS, GKE, or another Kubernetes distribution uses the same chart. The operator supplies the image repository, tag, pull secret, and resource values. Phase 1 does not create a cloud account, cluster, load balancer, certificate, or DNS record. The requirements are in [remote Kubernetes](remote-kubernetes.md).

## Publishing images

GitHub Actions builds and tests on pull requests. Trusted branch builds and `v*` tags push to GHCR with `GITHUB_TOKEN`. The image name is `ghcr.io/<owner>/bazaar-<application>:<tag>`. Fork pull requests do not receive permission to push. The rules are in [GitHub Actions and GHCR](github-actions.md).
