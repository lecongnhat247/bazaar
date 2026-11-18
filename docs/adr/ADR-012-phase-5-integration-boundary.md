# ADR-012: Phase 5 integration keeps the kind chart on the shop path

- Status: Accepted
- Date: 2026-11-18

## Context

Phase 5 adds Seller Center, Admin Portal, a recommendation service, and an analytics service. Each one already calls the gateway or consumes the existing Kafka topics. The kind node has about 7.6 GiB. SQL Server is a separate heavy process. The chart does not deploy databases.

## Decision

- The storefront, Seller Center, and Admin Portal keep the same Keycloak cookie and call the gateway from their own server. The browser does not receive another service's database.
- CI tests Seller Center, Admin Portal, the recommendation service, and the analytics service. The publish workflow builds those four images and pushes them to GHCR after the critical scan. Pull requests build the images and do not push them.
- The kind chart still schedules API Gateway, Identity, Catalog, Inventory, Order, Payment, Notification, and Storefront. `load-images.sh` loads only those images. Seller Center, Admin Portal, recommendation, and analytics run on Compose. SQL Server starts only with Compose profile `analytics` and is not a chart resource.
- Compose and kind still do not run together. The gateway route for analytics exists without a `depends_on`, so the shop starts when the analytics profile is off.

## Consequences

- A kind install does not serve `/api/v1/recommendations/**` or `/api/v1/analytics/**`, because those processes are not in the cluster. Compose does.
- Publishing an image does not place that process on the chart.
- The Java Testcontainers suites still need Docker. A green CI run of the new jobs does not by itself prove the full checkout saga on a laptop.

## Alternatives considered

Putting SQL Server and both desks on the kind chart would make one cluster contain every Phase 5 process, and it would also exceed the laptop node. Pointing the chart's gateway at services that are not scheduled would make those routes fail inside the pod.
