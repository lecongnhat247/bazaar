# ADR-007: Kafka in KRaft for local events

- Status: Accepted
- Date: 2026-11-07
- Superseded in part: M2 adds Kafka clients to catalog, inventory, order, and payment. Business facts are published from each service's outbox. See [the event contract](../../contracts/events/README.md).

## Context

Phase 2 checkout is one synchronous REST call. Phase 3 needs a broker before an outbox or a Saga can publish facts. [ADR-003](ADR-003-rest-communication.md) already fixed the envelope fields and at-least-once delivery. The laptop has 16 GB of RAM and the Docker Desktop VM is about 7.6 GiB. ZooKeeper would add a second process without a benefit on current Kafka.

## Decision

- Local Kafka is one combined KRaft node from `apache/kafka:4.1.1`. There is no ZooKeeper and no second broker.
- Spring Kafka 4.1.2 and the Kafka client 4.2.2 come from the Spring Boot 4.1.2 parent. The service does not pin another client version. The broker image stays on 4.1.1.
- Topics are `bazaar.order.events`, `bazaar.inventory.events`, `bazaar.payment.events`, and `bazaar.catalog.events`. Each has 3 partitions and replication factor 1. Automatic topic creation is off.
- The message key is the aggregate id. JSON text is the payload. Services keep their own DTOs.
- Order service is the only client in this milestone. Checkout does not publish business events.
- Readiness stays the process and PostgreSQL. Kafka has its own health group. A stopped broker must not fail the readiness probe that Compose and the Helm chart already use.
- The kind chart does not run Kafka.

## Consequences

- A message can arrive more than once. M2 stores processed event ids, so a duplicate does not apply a second business effect. M1 did not store them.
- A message that still fails after three attempts is logged and skipped. There is no dead-letter topic yet.
- The order service can start and serve checkout when Kafka is down. The consumer retries in the background.
- Replication factor 1 loses the only copy if the broker volume is removed.

## Alternatives considered

Debezium or Kafka Connect would publish the outbox without application code. That is more infrastructure than this milestone needs. A Confluent or Bitnami image would hide the KRaft settings behind a different environment dialect. The Apache image matches the Testcontainers broker used by the order-service tests.
