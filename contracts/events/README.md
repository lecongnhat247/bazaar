# Event contracts

Kafka carries JSON envelopes. A service does not share its Java domain classes with another service. `eventVersion` is the schema version named `schemaVersion` in [ADR-003](../../docs/adr/ADR-003-rest-communication.md).

Catalog, inventory, order, and payment publish the facts below from a local outbox table. The business row and the outbox row commit together. A poller sends the JSON after that commit and marks the row published only after the broker acknowledges it. If the process stops between the acknowledgement and that update, the same `eventId` is sent again. Each service stores `(consumer_name, event_id)` in `processed_events` and skips a duplicate. Delivery is at-least-once. This is not exactly-once. A consumer retries a transient failure with exponential backoff for a few seconds, then copies the message to `bazaar.dlq`. An invalid payload or a business rejection is not retried. An outbox row that still cannot be published after 5 attempts becomes `DEAD` and stays in that service's database.

Checkout is a saga. The HTTP call stores the order and returns `PENDING_INVENTORY`. The order service sends commands. Inventory and payment reply with events. The order changes state only when the reply matches the step it is in.

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
| `bazaar.order.events` | order id | 3 | 1 | order service | order service group `order-service`, notification group `notification-service` |
| `bazaar.inventory.events` | reservation id | 3 | 1 | inventory service | inventory service group `inventory-service` |
| `bazaar.payment.events` | payment id | 3 | 1 | payment service | payment service group `payment-service`, notification group `notification-service` |
| `bazaar.catalog.events` | product id | 3 | 1 | catalog service | catalog service group `catalog-service` |
| `bazaar.inventory.commands` | order id or reservation id | 3 | 1 | order service | inventory group `inventory-commands` |
| `bazaar.payment.commands` | order id or payment id | 3 | 1 | order service | payment group `payment-commands` |
| `bazaar.dlq` | original key | 3 | 1 | the consumer that gave up | no business consumer |

Commands, when they arrive, use a separate topic. They are requests. Events are facts that already happened. Local retention is one hour and 64 MB. That is not a production retention policy.

## Names

`eventType` is `<aggregate>.<fact>` in lower case. Dots separate words that belong together. `aggregateType` is `order`, `inventory`, `payment`, `catalog`, or `command`. `causationId` is empty. `eventVersion` is 1. `inventory.reserved` and `inventory.reservation-failed` use the order id as `correlationId`. `inventory.committed` and `inventory.released` use the reservation id, which is also the aggregate id. Payment facts use the payment id. The order saga matches those replies by the order id in the payload, or by the reservation id.

Commands from the order service:

- `inventory.reservation-requested`, `inventory.commit-requested`, `inventory.release-requested`.
- `payment.requested`, `payment.refund-requested`, `payment.cancel-requested`.

Produced when the business row changes:

- `order.pending-inventory`, `order.confirmed`, `order.payment-failed`, `order.cancelled`, `order.failed`. Payload: `status`, `currency`, `total`, `customerId`.
- `inventory.reservation-failed` and `inventory.commit-failed` when a command cannot be applied.
- `payment.refunded` when a successful payment is compensated. Payload includes `customerId`.
- `inventory.reserved`, `inventory.released`, `inventory.committed`, `inventory.expired`. Payload: `productId`, `quantity`, `status`.
- `payment.succeeded`, `payment.failed`, `payment.cancelled`. Payload: `orderId`, `customerId`, `amount`, `currency`, `status`.
- `catalog.product-published`, `catalog.product-unpublished`, `catalog.product-updated`. Payload: `name`, `sku`, `price`, `currency`, `status`.

Not produced: a stock quantity edit, a draft product create or edit, or a repeated call that does not change status. `inventory.reservation-failed` is produced when the reserve command cannot take stock. There is still no reservation row in that case.

`order.foundation-checked` is only the pipe used by the order-service test. It is not a checkout fact.

## Example

`order.confirmed` version 1, written after payment has succeeded and the stock hold has been committed:

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

## Dead letters

`bazaar.dlq` holds messages a consumer could not apply. The value is JSON with `originalTopic`, `eventId`, `correlationId`, `error`, and the original `value`. The notification service also includes `attempts`.

Do not republish a dead letter until the business state has been checked. If `processed_events` already has that `eventId`, the consumer will ignore a second copy. If it does not, republish only the original value to `originalTopic`, with the same key. That preserves the idempotency key.

An outbox row in `DEAD` was never acknowledged by the broker. Read `last_error`, `event_id`, and `correlation_id` before touching it. If the business row still wants that fact, set `status` back to `PENDING`, `retry_count` to 0, and `next_attempt_at` to null for that one `event_id`. Do not insert a second event id for the same fact.
