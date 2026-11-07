# Event contracts

Kafka carries JSON envelopes. A service does not share its Java domain classes with another service. `eventVersion` is the schema version named `schemaVersion` in [ADR-003](../../docs/adr/ADR-003-rest-communication.md).

Catalog, inventory, order, and payment publish the facts below from a local outbox table. The business row and the outbox row commit together. A poller sends the JSON after that commit and marks the row published only after the broker acknowledges it. If the process stops between the acknowledgement and that update, the same `eventId` is sent again. Each service stores `(consumer_name, event_id)` in `processed_events` and skips a duplicate. Delivery is at-least-once. This is not exactly-once, and there is no dead-letter topic yet. Checkout HTTP is unchanged.

## Envelope

| Field | Required | Meaning |
| --- | --- | --- |
| `eventId` | yes | Unique id of this delivery. Consumers use it to ignore duplicates. |
| `eventType` | yes | Past-tense business fact, such as `order.confirmed`. |
| `eventVersion` | yes | Schema version of that event type. Starts at 1. |
| `aggregateId` | yes | Id of the order, product, reservation, or payment. This is also the Kafka key. |
| `aggregateType` | yes | `order`, `inventory`, `payment`, or `catalog`. |
| `aggregateVersion` | no | Monotonic version of the aggregate when the producer has one. |
| `occurredAt` | yes | UTC timestamp when the fact happened. |
| `correlationId` | yes | Id shared by one checkout attempt. |
| `causationId` | no | `eventId` of the fact that caused this one. |
| `producer` | yes | Service name, such as `order-service`. |
| `payload` | no | JSON object. Receivers ignore unknown fields. |

A new required field needs a new `eventVersion`. Adding an optional field does not. Producers do not remove or rename a field inside an existing version.

## Topics

| Topic | Key | Partitions | Replication | Producer | Consumer |
| --- | --- | --- | --- | --- | --- |
| `bazaar.order.events` | order id | 3 | 1 | order service | order service group `order-service` |
| `bazaar.inventory.events` | reservation id | 3 | 1 | inventory service | inventory service group `inventory-service` |
| `bazaar.payment.events` | payment id | 3 | 1 | payment service | payment service group `payment-service` |
| `bazaar.catalog.events` | product id | 3 | 1 | catalog service | catalog service group `catalog-service` |

Commands, when they arrive, use a separate topic. They are requests. Events are facts that already happened. Local retention is one hour and 64 MB. That is not a production retention policy.

## Names

`eventType` is `<aggregate>.<fact>` in lower case. Dots separate words that belong together. `aggregateType` is `order`, `inventory`, `payment`, or `catalog`. `correlationId` is the same id as `aggregateId` for these facts. `causationId` is empty. `eventVersion` is 1.

Produced when the business row changes:

- `order.pending-payment`, `order.confirmed`, `order.payment-failed`, `order.cancelled`. Payload: `status`, `currency`, `total`.
- `inventory.reserved`, `inventory.released`, `inventory.committed`, `inventory.expired`. Payload: `productId`, `quantity`, `status`.
- `payment.succeeded`, `payment.failed`, `payment.cancelled`. Payload: `orderId`, `amount`, `currency`, `status`.
- `catalog.product-published`, `catalog.product-unpublished`, `catalog.product-updated`. Payload: `name`, `sku`, `price`, `currency`, `status`.

Not produced: a failed reservation, because that request rolls back and leaves no reservation; a stock quantity edit; a draft product create or edit; a repeated call that does not change status.

`order.foundation-checked` is only the pipe used by the order-service test. It is not a checkout fact.

## Example

`order.confirmed` version 1, written by checkout when payment succeeds:

```json
{
  "eventId": "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9001",
  "eventType": "order.confirmed",
  "eventVersion": 1,
  "aggregateId": "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9002",
  "aggregateType": "order",
  "occurredAt": "2026-11-07T00:00:00Z",
  "correlationId": "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9002",
  "producer": "order-service",
  "payload": {
    "status": "CONFIRMED",
    "currency": "USD",
    "total": "25.00"
  }
}
```

Money in a payload is a decimal string. The catalog remains the price authority for checkout.
