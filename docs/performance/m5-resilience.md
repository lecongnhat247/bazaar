# Phase 4 M5 resilience

Checkout now fails fast when catalog or inventory does not answer, and it does not create an order for that failure. The saga, the outbox, and the gateway Redis limit are unchanged. This is not a capacity claim.

## Timeout budget

Measured catalog reads in the M2 baseline were about 20 ms at p99. The budgets below sit far above that, and the gateway budget stays above one checkout price lookup.

| Hop | Budget | What happens at the limit |
| --- | --- | --- |
| Gateway connect | 300 ms | The route is not opened |
| Gateway response | 5 s | The caller sees the gateway timeout |
| Order service connect | 300 ms | The price lookup becomes HTTP 503 |
| Order service read | 800 ms | The same 503. One read retry is allowed |
| Read retry wait | 20 ms, then exponential backoff with jitter, at most one retry | A timeout is not tried a third time |
| Worst price lookup | about 1.7 s | Still inside the gateway's 5 s |
| Hikari connection wait | 2 s, pool size 10 | A checkout does not sit for the old 30 s |
| Kafka listener stop | 10 s | Inside the 20 s graceful shutdown |
| Helm termination grace | 30 s for Java, already set | Covers that shutdown |

The HTTP read timeout is the time limit. There is no second Resilience4j time limiter around it.

## What protects a call

| Mechanism | Where | Rule |
| --- | --- | --- |
| Retry | Order service GET of a product or a campaign | At most 2 attempts. Only HTTP 408, 500, 502, 503, and 504. A 404 or 409 is not retried |
| Circuit breaker | Separate breakers for catalog, inventory, and payment | Opens after at least 5 calls in a window of 10 when half of them fail, or when every call is slower than 800 ms. Stays open for 10 s, then allows one trial |
| Bulkhead | All order-service downstream calls | 8 at a time. The 9th fails immediately. The Hikari pool stays at 10 |
| Rate cap | Those same calls | 30 calls per second, no queue. This is a stampede cap |
| Customer rate limit | Gateway Redis | Still 120 reads and 30 writes per minute. A Redis miss still allows the request |
| Saga and outbox | Existing command retry | Still the only retry for reservation, payment, and events. Resilience4j does not wrap them |

A POST to payment or inventory is not retried here. Checkout does not use those HTTP methods. Payment failure still follows the saga: the order remains, stock is released, and a commit that fails after payment asks for a refund.

An open circuit, a full bulkhead, or the rate cap becomes HTTP 503: `Checkout could not be completed. Retry with the same Idempotency-Key.` No order row is written.

## Degradation

| Dependency | Result |
| --- | --- |
| Catalog down during checkout | 503, no order |
| Inventory down during a campaign checkout | 503, no order |
| Catalog Redis down | The cache miss reads Postgres. The gateway still serves |
| Kafka down | Readiness stays up. The outbox keeps the command |
| Postgres down | Readiness goes down |
| Notification down | Checkout does not call it. The order is stored and the event waits in the outbox |
| Too many downstream calls | The bulkhead rejects the extra ones |

Kafka consumers poll at most 20 records, matching the outbox batch. Notification consumes one partition at a time. Shutdown hooks disconnect that consumer on SIGTERM. `server.shutdown=graceful` was already set.

## Tests

| Module | Result |
| --- | --- |
| order-service | 31 tests, 0 failures. Includes an open catalog circuit with no new order, a 404 that is called once and does not open the circuit, a campaign checkout against a down inventory, and 12 slow calls of which at most 8 reach catalog |
| api-gateway | 8 tests, 0 failures. The Redis-loss test still allows the request |
| notification-service | 10 tests, 0 failures |

The order suite runs without a notification process, and checkout still creates the order. Payment failure, reservation failure, and commit failure are the existing saga tests. Readiness is asserted separately from the Kafka health group.

## Live check

Order and the gateway were rebuilt and recreated with `--no-deps` on 8 November 2026. Catalog was then stopped.

| Check | Result |
| --- | --- |
| Checkout while catalog was stopped | HTTP 503 in 2.9 s, body `Checkout could not be completed. Retry with the same Idempotency-Key.` |
| Orders | 55 before and 55 after |
| Lamp / shirt | available 1 and 2, reserved 0, unchanged |
| Catalog after start | Healthy. `GET /api/v1/products/8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001` returned 200, Desk lamp, 25.00 |

The 2.9 s includes the one allowed read retry and is inside the gateway's 5 s response budget. The caller received the order service's 503, not a gateway timeout.

## Not induced

The Hikari pool was not driven to exhaustion. Kafka and Postgres were not stopped on the shared Compose stack. Consumer lag was not measured again. The 30-per-second cap was not reached by the 12-call bulkhead test.
