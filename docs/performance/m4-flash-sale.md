# Phase 4 M4 flash sale

A campaign is a quota on one product, stored in `inventory_db` next to warehouse stock. Checkout still goes through the order saga. There is no flash-sale service. This run is one small check on the laptop. It is not a capacity claim.

## Where the rules live

| Rule | Where it is enforced |
| --- | --- |
| Campaign stock | `UPDATE flash_campaign SET remaining = remaining - ? WHERE status = 'ACTIVE' AND remaining >= ? AND starts_at <= now AND ends_at > now` |
| Warehouse stock | The existing `UPDATE inventory_item SET available = available - ? WHERE available >= ?` |
| Per-customer limit | `INSERT ... SELECT WHERE quantity <= limit`, and on conflict `UPDATE ... WHERE held + committed + excluded <= limit` |
| Price | The order service reads the campaign and stores that unit price. Inventory does not trust the order total; it only spends quota and stock |
| Duplicate checkout | The order idempotency key. The same saga key does not decrement quota again |
| Expired or released hold | The same transaction returns warehouse stock and campaign `remaining`, and decreases `held` |
| Committed sale | `held` moves to `committed`. `remaining` stays down |

`remaining + held + committed` equals `quota`. Reconcile may only lower `remaining` when that sum is above the quota. It never adds quota back.

Redis is not a second quota. The gateway still counts requests in Redis and fails open. A stale Redis value cannot approve or refuse the sale. Admission is the Postgres update.

A campaign checkout is one line, and that line is the campaign product. The catalog product must still be `ACTIVE`. The campaign price replaces the catalog price for that order. A normal checkout leaves `campaign_id` null and is unchanged.

Lock order for a campaign reservation is the product row, then the campaign row. Cancel locks the product first, then the campaign, then the held reservations, and releases them in that same transaction. Expiry still locks the reservation with `SKIP LOCKED`, returns stock, then restores the campaign.

Status moves `DRAFT` to `SCHEDULED` or `ACTIVE` when the seller schedules it. A read or a reservation promotes `SCHEDULED` to `ACTIVE` inside the window, and `SCHEDULED` or `ACTIVE` to `ENDED` once `ends_at` has passed. Cancel is allowed from `DRAFT`, `SCHEDULED`, or `ACTIVE`. An already held reservation can still commit until its TTL. A new reservation after `ENDED` updates zero rows.

Quota and the per-customer limit are request data. The API accepts a quota up to 100,000 and a limit up to 10,000. One thousand is only an example.

## Tests

Testcontainers, one class per service, run on this machine while Compose was up:

| Module | Result |
| --- | --- |
| inventory-service | 26 tests, 0 failures |
| order-service | 27 tests, 0 failures |

The inventory cases cover a quota of 15 with 30 concurrent attempts (exactly 15 holds), one customer stopped at a limit of 2, a quota of 1,000 with 40 attempts (remaining 960), expiry, commit, release, a repeated saga key, cancel, and reconcile. The order case checks that a campaign checkout is priced at 9.00 instead of the catalog price and that the reservation command carries the campaign id.

## Live check

Same laptop as the M2 baseline: 12th Gen i7-1260P, 16 GB RAM, Docker Desktop VM about 7.6 GiB. Hardware was not measured again. Compose was already up. Inventory and order were rebuilt. Flyway applied `V7__flash_campaign` on `inventory_db` and `V8__order_campaign` on `order_db`. Hikari stays at 10. Isolation stays `READ COMMITTED`.

k6 image `grafana/k6`, script `infrastructure/k6/flash-sale.js`, 8 November 2026. `RUN_ID=m4a`. No separate warm-up. One scenario: `shared-iterations`, 4 VUs, 8 iterations, max duration 30 seconds. The run finished in about 5 seconds. One customer token. Setup created a new catalog product, set warehouse stock to 20, and scheduled a campaign with quota 5, per-customer limit 8, and price 9.00. The catalog price of that product was 12.00. The lamp and shirt seeds were not used.

All 8 checkouts were accepted (HTTP 201). That only means the order service stored the order. The saga then reserved stock. Final rows:

| Check | Result |
| --- | --- |
| Campaign | `ACTIVE`, quota 5, remaining 0, held 0, committed 5 |
| Reservations | 5 `COMMITTED`, none held, released, or expired |
| Warehouse stock | available 15, reserved 0. Started at 20 |
| Orders | 5 `CONFIRMED` at 9.00 each (45.00), 3 `FAILED` at 9.00 each |
| Negatives | 0 |
| Lamp / shirt | available 1 and 2, reserved 0, unchanged |
| `GET /api/v1/flash-sales` | 404 |
| Replay of `m4m4av1i6` | HTTP 200, same confirmed order. Order count stayed 8. Quota stayed 0 / 0 / 5 |
| `bazaar_flash_reservations_total` | reserved 5, rejected 3 |

k6 `http_req_failed` was 0 on 17 requests (setup, 8 checkouts, campaign polls). No 429. `http_req_duration` mixes setup and checkout, so it is not a checkout latency. Iteration duration, including the 200 ms pause, was median 664 ms and max 1.02 s. The campaign `remaining` was already 0 when the first teardown poll ran.

The three `FAILED` orders are the quota working. Checkout accepts before the reservation, so eight accepts against five units leave three orders without a hold. Those orders do not keep stock or quota.

## Limitations

The live run used one customer and a limit above the attempt count, so it shows the quota, not the per-customer limit. The limit is covered by the Testcontainers case. Eight accepts is inside the gateway write limit of 30 a minute per client. A larger run from this container would measure 429 responses. The counters do not include customer or order ids. A held reservation that outlives `ends_at` can still commit until it expires. Reconcile was not required on the live rows; the sum already matched the quota.
