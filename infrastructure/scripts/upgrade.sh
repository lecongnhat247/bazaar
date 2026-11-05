#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

helm upgrade bazaar "$root/infrastructure/helm/bazaar" \
  --namespace bazaar \
  --wait \
  --timeout 8m \
  "$@"
