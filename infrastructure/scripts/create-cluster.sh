#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ -n "$(docker compose -f "$root/docker-compose.yml" ps -q)" ]]; then
  echo "Stop Docker Compose before creating the kind cluster: docker compose down" >&2
  exit 1
fi

if kind get clusters | grep -qx bazaar; then
  echo "Cluster bazaar already exists." >&2
  exit 1
fi

kind create cluster --config "$root/infrastructure/kind/cluster.yaml"
kubectl config current-context
kubectl get nodes
