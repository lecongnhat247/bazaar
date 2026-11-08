#!/usr/bin/env bash
set -euo pipefail

images=(
  bazaar-api-gateway:latest
  bazaar-identity-service:latest
  bazaar-catalog-service:latest
  bazaar-inventory-service:latest
  bazaar-order-service:latest
  bazaar-payment-service:latest
  bazaar-storefront:latest
  bazaar-notification-service:latest
)

for image in "${images[@]}"; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "Missing local image ${image}. Build it with: docker compose build" >&2
    exit 1
  fi
  echo "Loading ${image}"
  kind load docker-image "$image" --name bazaar
done
