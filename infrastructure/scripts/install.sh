#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

helm upgrade --install bazaar "$root/infrastructure/helm/bazaar" \
  --namespace bazaar \
  --create-namespace \
  --wait \
  --timeout 8m \
  "$@"
