#!/usr/bin/env bash
# Apply the chart with an immutable image tag.
# --render prints the manifests and does not contact a cluster.
# A failed upgrade rolls back to the last successful release.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "$root/infrastructure/scripts/release-tag.sh"

render=0
if [[ "${1:-}" == "--render" ]]; then
  render=1
  shift
fi

tag="${1:-}"
if [[ $# -gt 0 ]]; then
  shift
fi
reject_release_tag "$tag"

chart="$root/infrastructure/helm/bazaar"
if [[ "$render" -eq 1 ]]; then
  helm template bazaar "$chart" \
    --namespace bazaar \
    "$@" \
    --set "image.tag=${tag}"
  exit 0
fi

echo "Deploying tag ${tag} into namespace bazaar."
helm upgrade --install bazaar "$chart" \
  --namespace bazaar \
  --create-namespace \
  --rollback-on-failure \
  --wait \
  --timeout 8m \
  "$@" \
  --set "image.tag=${tag}"

echo "Checking rollout status."
for name in api-gateway identity-service catalog-service inventory-service order-service payment-service notification-service storefront; do
  kubectl --namespace bazaar rollout status "deployment/${name}" --timeout=180s
done
