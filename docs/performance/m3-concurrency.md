# Phase 4 M3 concurrency

Inventory and order stay on JDBC. Catalog stays on JPA. The stock rule is still one conditional update, not a version column and not a read followed by a separate write.

## Why each lock was chosen

| Path | Strategy | Why |
| --- | --- | --- |
| Stock reservation, commit, release, and return | `UPDATE ... WHERE available >= ?` or `WHERE reserved >= ?`, plus `CHECK (available >= 0)` and `CHECK (reserved >= 0)` | The predicate is the invariant. Two reservations of the last unit serialize on the product row. The loser updates zero rows and is rejected. A `@Version` check would not stop two decrements that both observed the old quantity. |
| Reservation expiry | `SELECT ... FOR UPDATE SKIP LOCKED` | Two expirers must not return the same hold. A row already locked by the other expirer is skipped. |
| Saga reservation of several products | Lock products in `product_id` order, then retry SQLSTATE `40P01` | Opposite item order used to lock row A then B in one transaction and B then A in the other. Postgres aborts one of those waits. Sorting removes that cycle. If a deadlock still happens, the command consumer retries the reservation transaction up to three times. That inner transaction is `REQUIRES_NEW`, so the retry does not reuse the aborted one. |
| Order status | `UPDATE customer_order SET status = ? WHERE id = ? AND status = ?` | The current status is the optimistic check. One transition wins. A numeric version would also reject a payment-state write that races a legal status change. The history sequence is taken after that update, while the order row lock is still held. |
| Duplicate checkout or reservation key | Unique `idempotency_key` | The losing insert rolls back with its stock change or order row. The caller then reads the winning row. |
| Product edit | JPA `@Version` on `product.version` | A seller save loads the product, changes the price or name, and writes it back. Two overlapping saves of version 0 cannot both commit. The loser is HTTP 409, `The product was changed by a concurrent update.` |

Isolation stays `READ COMMITTED`. The predicates above are the business rule. `SERIALIZABLE` would abort more reservations without a stronger stock guarantee.

The Hikari pool stays at its default maximum of 10 connections per service. During the M2 baseline the pool had no pending threads. Raising it would add connections from every service onto the one local Postgres.

## Queries

`EXPLAIN` on the Compose database, 8 November 2026:

| Query | Plan at the current size |
| --- | --- |
| Reserve one known product | Sequential scan. `inventory_item` has three rows and the primary key is already the product id. No new index. |
| List a customer's orders | Sequential scan, then a sort. The index `(customer_id, created_at DESC)` already exists. The planner prefers the scan at about 47 orders. No second index. |
| Expire held reservations | Sequential scan. The index `(status, expires_at)` already exists. The reservation table is still small. No second index. |
| Find an order line by `reservation_id` | Sequential scan, cost 3.59. There was no index on that column. |

The new index is `order_item_reservation_idx` on `order_item (reservation_id)`. Saga commit and release look up a line by reservation id. Each new line also writes one index entry, and the entry is updated when the reservation id is filled in. With `enable_seqscan` turned off, Postgres uses that index at cost 8.16. At today's row count the sequential scan is cheaper (3.59), so the planner still scans. The index is for the table after it grows.

Order listing no longer loads each order, its lines, and its history as separate queries. One page is three queries: the orders, their lines, and their history. The page is still the latest 20 orders.

## Tests

Testcontainers, one class per service, run on this machine while Compose was up:

| Module | Result |
| --- | --- |
| inventory-service | 18 tests, 0 failures. Includes eight racing reservations of five units, eight callers with one idempotency key, commit against release, opposite product order, and one raw deadlock where Postgres aborts exactly one transaction. |
| order-service | 26 tests, 0 failures. Includes six concurrent checkouts with one idempotency key, and two concurrent cancels of one order. |
| catalog-service | 20 tests, 0 failures. Includes two overlapping product edits, one of which keeps version 1, and the 409 mapping for a stale write. |

After those tests, Compose rebuilt catalog, inventory, and order. Flyway `V7__order_item_reservation_idx` and `V7__product_version` both succeeded. Existing products are version 0. A product read and an order list through the gateway both returned 200. Lamp stock stayed at available 1, and the shirt stayed at available 2, both with reserved 0.

## Limitations

This does not measure production throughput. The new reservation index does not change the plan while `order_item` is this small. A deadlock that survives three retries still fails the Kafka delivery, and the listener rolls back its processed-event row so the broker can deliver it again. Flash Sale quota is a later milestone.
