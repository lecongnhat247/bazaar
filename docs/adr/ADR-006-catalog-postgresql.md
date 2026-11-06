# ADR-006: Catalog data lives in PostgreSQL

- Status: Accepted
- Date: 2026-11-05
- Supersedes: the catalog row of [ADR-005](ADR-005-database-ownership.md) for Phase 2

## Context

ADR-005 assigned the catalog to MongoDB because product attributes differ by category. Phase 2 keeps one PostgreSQL instance for local development and gives each business service its own database. A second database engine is not justified yet. PostgreSQL JSONB can store category-specific attributes without sharing a schema with inventory, orders, or payments.

Analytics on SQL Server, and Kafka and Redis, stay out of this phase.

## Decision

Catalog Service owns `catalog_db` on PostgreSQL. Flexible attributes, when they are added, use JSONB in that database. MongoDB is not introduced.

The other ownership rules in ADR-005 still apply. Identity, inventory, orders, and payments keep separate PostgreSQL databases. No service reads another service's tables. Keycloak uses `keycloak_db`, and business services do not connect to it.

Flyway owns the schema. Hibernate does not create or alter tables.

## Consequences

- Local Compose runs one Postgres container and six databases. Production can still split those databases onto separate servers without changing service code.
- A category-specific attribute query is SQL against JSONB, not a document query.
- The kind chart does not run Postgres or Keycloak. A cluster deploy needs those stores before the Phase 2 services can become ready.

## Alternatives considered

Keeping MongoDB for the catalog matches ADR-005 and adds a second engine, image, and backup path before the catalog model needs it. A shared `public` schema would let a join cross service boundaries, which ADR-005 rejects.
