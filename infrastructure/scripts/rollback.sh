#!/usr/bin/env bash
# Restore a numbered Helm revision. Revision 0 is refused so the image tag stays explicit.
set -euo pipefail

revision="${1:-}"
if [[ ! "$revision" =~ ^[1-9][0-9]*$ ]]; then
  echo "Usage: rollback.sh <revision>" >&2
  echo "List revisions with: helm history bazaar --namespace bazaar" >&2
  echo "Then pass that revision number. This script does not roll back until the number is present." >&2
  exit 2
fi

echo "Rolling back bazaar to revision ${revision}."
helm rollback bazaar "$revision" \
  --namespace bazaar \
  --wait \
  --timeout 8m

echo "Checking rollout status."
for name in api-gateway identity-service catalog-service inventory-service order-service payment-service notification-service storefront; do
  kubectl --namespace bazaar rollout status "deployment/${name}" --timeout=180s
done
