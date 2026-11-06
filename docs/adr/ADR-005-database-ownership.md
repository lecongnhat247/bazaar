# ADR-005: Database ownership per service

- Status: Accepted
- Date: 2026-11-04
- Superseded in part: the catalog store is PostgreSQL from Phase 2. See [ADR-006](ADR-006-catalog-postgresql.md).

## Context

BAZAAR will store identity, catalog, inventory, orders, and payments. Those models change for different reasons and have different consistency needs. The catalog needs flexible category-specific attributes. Inventory needs atomic stock updates. Orders and payments need transactional records. Analytics needs a separate read model.

Phase 1 services are stateless and must not take a database driver yet. The ownership decision is recorded now so later milestones do not share tables or hide a join across services.

## Decision

Each service that stores data owns that data. No other service connects to it.

| Owner | Store | When |
| --- | --- | --- |
| Identity Service | PostgreSQL | Later phase |
| Catalog Service | MongoDB | Later phase |
| Inventory Service | PostgreSQL | Later phase |
| Order Service | PostgreSQL | Later phase |
| Payment Service | PostgreSQL | Later phase |
| Analytics Service | SQL Server | Later phase, owned by the .NET service |
| API Gateway | None | — |
| Storefront | None | — |

Rules that apply when the stores are added:

- Schema changes ship with the owning service, through Flyway for PostgreSQL.
- Another service gets data through that service's API or through an event.
- Inventory quantities live only in the inventory store.
- Production data services are expected to be managed outside the application cluster. A container database is a local-development option, not the remote deployment design.
- Redis, when introduced, is for cache, rate limits, and short-lived coordination. It is not the system of record.

Phase 1 does not create database instances, drivers, or migrations.

## Consequences

- A checkout flow cannot update stock and an order in one database transaction. Later coordination uses the order service and explicit compensation, then a Saga.
- Operational joins across orders and products happen in a client or in the analytics read model.
- Phase 1 health checks have no database dependency, so readiness means the process is serving HTTP.

## Alternatives considered

One shared PostgreSQL schema would make the first queries easier and would couple deployments. Putting product attributes and stock in one document would let the catalog and inventory change together, which breaks the inventory consistency boundary.
