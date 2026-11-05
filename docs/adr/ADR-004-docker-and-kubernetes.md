# ADR-004: Docker and Kubernetes deployment strategy

- Status: Accepted
- Date: 2026-11-04

## Context

The same skeleton must run as local processes, as Docker Compose, and as a single-node kind cluster, and the manifests must remain usable on a remote Kubernetes cluster later. Docker Desktop is the container runtime. Its VM has about 7.6 GiB of memory. kind 0.33.0, Helm 4.3.1, and kubectl 1.37.2 are installed in WSL Ubuntu. Windows kubectl from Docker Desktop is 1.36.1 and is the wrong client for a 1.37 API.

Images must not contain laptop paths, environment DNS names, or secrets.

## Decision

- Build one image per application with a multi-stage Dockerfile and a non-root user.
- Use Eclipse Temurin `25.0.5_8-jdk-noble` to build Java services and `25.0.5_8-jre-noble` to run them. Use `node:24.22.2-bookworm-slim` for the storefront. These tags were confirmed in M4. The host Node.js is 24.22.1; the image uses the current 24.22 patch.
- Pass every address through environment variables. Keep one image for local, Compose, kind, and future remote clusters.
- Use Docker Compose with the `bazaar` network for local containers.
- Use Helm for Kubernetes. Keep image repository, tag, and pull policy in values.
- Create kind with one control-plane node and this image: `kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5`.
- Run kind, Helm, and kubectl from WSL Ubuntu.
- Load local images with `kind load docker-image`. Use `imagePullPolicy: IfNotPresent` for that workflow.
- Expose the storefront and the gateway to the host with `kubectl port-forward`.
- Set requests and limits on every container, and keep Compose and kind from running at the same time.
- Publish images to GHCR from GitHub Actions on trusted branches, using `GITHUB_TOKEN`. Pull-request workflows from forks must not push images.
- Leave ingress, TLS, DNS, and cloud provisioning out of Phase 1.

The storefront reads `API_GATEWAY_URL` on the server for each connectivity check. The browser calls the storefront origin, so the image does not embed `localhost` or a cluster DNS name.

Container health for the storefront is its own HTTP root. It does not depend on the gateway.

## Consequences

- Developers use WSL for cluster commands and Windows or WSL Docker for image builds. Both talk to Docker Desktop.
- A remote cluster needs a values file that points at GHCR and a pull secret if the package is private. The secret is created in the cluster, not committed.
- Port-forward is a development access path. A later ingress decision can sit in front of the same Services.
- Memory limits are tight for seven JVMs plus a kind node. Phase 1 stays inside that budget by omitting databases and brokers.

## Alternatives considered

An ingress controller would add another component before the Services are proven. Kompose-style conversion of Compose would make Compose the source of Kubernetes configuration, which conflicts with portable Helm values. Installing kind and Helm again on Windows would duplicate the working WSL toolchain.
