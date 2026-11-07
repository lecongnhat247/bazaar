# ADR-003: REST communication for the initial skeleton

- Status: Accepted
- Date: 2026-11-04
- Superseded in part: Phase 3 adds a KRaft broker beside REST. Checkout stays synchronous until the Saga milestone. See [ADR-007](ADR-007-kafka-kraft.md).

## Context

Phase 1 must show a working path from the storefront and the gateway to each backend service. The long-term architecture uses Kafka for domain events, a transactional outbox, and a Saga for checkout. Those pieces need databases and business workflows that are explicitly out of Phase 1.

The skeleton still needs a real network path so Compose and Kubernetes can prove service discovery.

## Decision

- Phase 1 communication is synchronous HTTP.
- External API calls enter through the API Gateway.
- The gateway forwards a path prefix to the downstream base URL configured for that service.
- Backend services do not call each other in Phase 1.
- The storefront server calls only the gateway health endpoint, through `API_GATEWAY_URL`.
- No service is given a Kafka client, an outbox table, or a REST client for another business service.
- Later event-driven flows will use an envelope with `eventId`, `eventType`, `aggregateId`, `aggregateVersion`, `occurredAt`, `schemaVersion`, `correlationId`, `causationId`, and `payload`.
- Later consumers will treat delivery as at least once and make the business effect idempotent.

## Consequences

- Phase 1 failure handling is an HTTP status and a health check. There is no retry or compensation workflow yet.
- Checkout coordination is not implemented. When it arrives, the first version may be synchronous, and the event-driven Saga replaces it in a later phase.
- Adding Kafka later does not require changing the Phase 1 info and health contracts.

## Alternatives considered

Introducing Kafka in the skeleton would add a broker, topic contracts, and consumer groups before there is a business event to carry. That cost belongs with the first real workflow, not with health endpoints.
