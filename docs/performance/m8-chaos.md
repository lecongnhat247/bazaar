# Phase 4 M8 chaos and capacity

One Compose service was stopped at a time on 8 November 2026. The check used the running stack on this laptop. It did not start kind, did not install a chaos platform, and did not delete volumes. This is not a production capacity result.

The machine is the same one measured in M2: 12th Gen i7-1260P, 16 GB RAM, Docker Desktop VM about 7.6 GiB. Hardware was not measured again.

## What was stopped

| Service stopped | Call | Result |
| --- | --- | --- |
| Catalog | Checkout | HTTP 503 in 2.2 s. Body: `Checkout could not be completed. Retry with the same Idempotency-Key.` Orders stayed 55 |
| Order | Checkout | HTTP 500 in 322 ms from the gateway. The order process was not there to answer |
| Redis | `GET /api/v1/products/{lamp}` | HTTP 200 in 803 ms |
| Kafka | Checkout of a new product, outcome `SUCCESS` | HTTP 201 in 722 ms. After Kafka was healthy the order was `CONFIRMED` |
| Postgres | `GET /api/v1/products/{lamp}` | HTTP 504 in 5.1 s while Postgres was stopped |

An earlier catalog stop, before the script was re-run, returned HTTP 504 in 9.0 s and also left the order count at 55. The 2.2 s result is the one recorded in `infrastructure/k6/results/m8-chaos.json`.

Lamp available stayed 1 and shirt available stayed 2, reserved 0, after every stop. The disposable product used while Kafka was down went from 4 available to 3, reserved 0.

## Recovery

| Check | Result |
| --- | --- |
| Catalog, order, Redis, Kafka | `docker compose start` returned each one to healthy |
| Saga after Kafka returned | The accepted order reached `CONFIRMED` |
| Outbox | 0 unpublished rows for that order |
| Same idempotency key | HTTP 200. One order row. One `SUCCEEDED` payment |
| Consumer lag | Sampled groups, including `inventory-commands`, showed lag 0. A large lag was not created |
| Postgres read after health | The first product GET was still HTTP 504. A later GET of the lamp returned HTTP 200 in 121 ms |
| Keycloak after Postgres | The container was healthy, and the token request returned HTTP 500 because the database connection was already closed. `docker restart bazaar-keycloak-1` made the next token HTTP 200 |

## Flash sale

`infrastructure/k6/flash-sale.js`, `RUN_ID=m8a`, quota 5, per-customer limit 8, warehouse 20, 4 VUs, 8 iterations. The lamp and shirt were not used.

| Fact | Value |
| --- | --- |
| Campaign | `ad6b712c-976a-4320-86e2-3671f3a6ed34` |
| Product | `680a342d-331d-4295-be1b-95b8f8190ebf` |
| HTTP checks | 9 of 9 passed. No failed HTTP request in the k6 summary |
| Orders | 5 `CONFIRMED`, 3 `FAILED` |
| Quota | remaining 0, held 0, committed 5 |
| Warehouse | available 20 to 15, reserved 0 |

Five confirmations match the quota. The warehouse fell by the same five. The run finished in about 2 seconds. Checkout HTTP duration in that summary was p50 103 ms and p95 125 ms. That is the accept call, not a capacity limit.

## Catalog load

`infrastructure/k6/catalog.js`, 2 VUs, 15 seconds, after the stops and the Keycloak restart.

| | M2 listing | This run |
| --- | --- | --- |
| Rate | about 1.7 requests/s | 6.1 requests/s |
| Requests | 50 | 93 |
| Failures | 0 | 0 |
| p50 | 11.4 ms | 15.1 ms |
| p95 | 16.0 ms | 35.3 ms |
| Max | 16.7 ms | 310 ms |

The threshold in the script is p95 under 1 s. It passed. The higher percentile sits next to a higher rate and next to a stack that had just restarted Postgres. It is not a tuned result and it is not a production capacity number.

## Resource sample

One `docker stats` sample while the stack was up, before the Postgres stop. Every listed container was inside its memory limit. Examples: gateway 294 MiB of 512 MiB, order 319 MiB of 512 MiB, Kafka 464 MiB of 768 MiB, Keycloak 566 MiB of 768 MiB. Kafka CPU on that sample was 163 percent, which was one instant during broker recovery, not a sustained load test. Memory pressure and CPU saturation were not driven on purpose.

## Not induced

Network delay was not injected. Consumer lag was not built up. A kind pod was not deleted, because Compose was the running stack and the two are not run together. The gateway write limit and the order-service bulkhead were not driven to their caps. GitHub Actions for `069536f` was already red and was not re-run here.
