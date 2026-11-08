# Phase 4 M2 baseline

This is a local measurement of the Compose stack on one laptop. It is not a production capacity result, and it does not change checkout, the outbox, or inventory reservation.

## Hardware and environment

| Item | Value |
| --- | --- |
| Machine | 12th Gen Intel Core i7-1260P, 12 cores, 16 threads |
| RAM | 16,849,879,040 bytes (about 15.7 GiB) |
| Docker Desktop VM | 7.603 GiB |
| OS | Windows, tests launched from Git Bash |
| Stack | `docker compose` from `D:\bazaar`, kind not running |
| k6 | `grafana/k6` digest `sha256:3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e` |
| Client path | k6 container to `http://host.docker.internal:8080` and Keycloak at `http://host.docker.internal:8088` |
| When | 2026-11-08, about 09:14–09:38 UTC |

Application configuration is the Compose file as of the M1 commit, plus this baseline script. Java services are limited to 512 MB, Postgres to 512 MB, Kafka and Keycloak to 768 MB, Redis to 128 MB. The gateway allows 120 reads and 30 writes per 60 seconds for one client, and it stays open if Redis does not answer within 300 ms. Postgres is the source of truth. Redis caches public catalog reads and holds rate-limit counters. Checkout price reads use the catalog database. Tracing sample probability is 1.0. There is no trace database.

## Workload

Warm-up is 10 seconds, 1 virtual user, product listing only. Those requests are not included in the listing row below.

Measured reads use 2 virtual users for 30 seconds with a 1.2 second pause. That stays under the 120 reads-per-minute gateway limit for a single client address. Writes use one customer (`customer` / `customer_local`) and stay inside a short burst rather than the 30 writes-per-minute ceiling for long.

| Scenario | Virtual users | What was sent |
| --- | --- | --- |
| Product listing | 2 for 30s | `GET /api/v1/products?page=0&size=20` |
| Product detail | 2 for 30s | `GET /api/v1/products/{lamp}` |
| Product search | 2 for 30s | `GET /api/v1/products?q=lamp&page=0&size=20` |
| Category filtering | 2 for 30s | `GET /api/v1/products?categoryId={home}&page=0&size=20` |
| Normal checkout | 1 user, 4 iterations | `POST /api/v1/orders/checkout`, shirt quantity 1, outcome `FAILURE` |
| Concurrent checkout | 2 users, 4 iterations | Same `FAILURE` checkout |
| Inventory reservation | 1 user, 3 iterations | Reserve one shirt, then release that reservation |
| Flash Sale traffic | 1 request | `GET /api/v1/flash-sales` |
| Order status | 1 user for 20s | `GET /api/v1/orders` and `GET /api/v1/orders/{known confirmed order}` |
| One successful checkout | 1 iteration | Same shirt, quantity 1, outcome `SUCCESS` |

`FAILURE` is the existing payment-failure path. The order is accepted, then the saga should end `PAYMENT_FAILED` and return the stock. `SUCCESS` completes the saga and decrements `available` by 1. Idempotency keys include `RUN_ID`, so a repeated run with the same id would replay instead of creating orders.

The read, reservation, order-status, and success numbers below are from one full run of `baseline.js` (`RUN_ID` default `a` on that run). A later short run (`ONLY=checkout`, `RUN_ID=c`) separated normal checkout latency from concurrent checkout latency. The first full pass used the same workload and the same business outcome; it did not keep per-scenario trends.

## Result metrics

k6 HTTP error rate on the full run was 0 (262 requests, `http_req_failed` 0, 260 checks passed, 0 failed). No gateway 429 appeared in the Prometheus rate for that window. Read throughput is about 50 requests / 30 seconds = 1.7 requests per second. The suite-wide rate is about 0.74 requests per second because the scenarios run one after another and pause between them.

Latencies are milliseconds, measured by k6 from the client.

| Scenario | Count | p50 | p90 | p95 | p99 | Max |
| --- | --- | --- | --- | --- | --- | --- |
| Listing | 50 | 11.4 | 14.2 | 16.0 | 16.6 | 16.7 |
| Detail | 50 | 10.7 | 14.5 | 17.1 | 18.8 | 18.9 |
| Search | 50 | 13.4 | 19.5 | 22.6 | 24.4 | 24.5 |
| Category | 50 | 9.7 | 11.7 | 15.2 | 16.0 | 16.0 |
| Reservation | 3 | 18.2 | 41.7 | 44.6 | 47.0 | 47.6 |
| Reservation release | 3 | 15.1 | 16.4 | 16.6 | 16.8 | 16.8 |
| Order list | 17 | 18.9 | 26.7 | 29.9 | 37.6 | 39.5 |
| Order detail | 17 | 10.2 | 15.3 | 17.0 | 18.8 | 19.2 |
| Successful checkout HTTP accept | 1 | 30 | 30 | 30 | 30 | 30 |

Checkout accept latency, from the short follow-up run only:

| Scenario | Count | p50 | p90 | p95 | p99 | Max |
| --- | --- | --- | --- | --- | --- | --- |
| Normal checkout (`FAILURE`) | 4 | 27.5 | 181 | 214 | 240 | 247 |
| Concurrent checkout (`FAILURE`) | 4 | 110 | 215 | 224 | 230 | 232 |

On the full run the eight `FAILURE` accepts were still one trend: p50 37 ms, p95 138 ms, max 150 ms. Four and eight samples are too small for a stable percentile. The HTTP 201 is only the accept. Saga completion is slower. The two successful checkouts in this milestone were confirmed 3.66 seconds and 5.22 seconds after the order row was created (`ca2f4c8a-f5c9-4d17-9fef-184a930d0e72` and `680a09d9-9e0e-4ef1-b429-4a65e8b86a08`).

Flash Sale returned HTTP 404. That scenario sent one request and did not generate load. The campaign does not exist yet.

Prometheus over the full measured run:

| Signal | Observation |
| --- | --- |
| Gateway average latency | products 7.1 ms, orders 14.0 ms, inventory 15.0 ms |
| Catalog cache | 154 hits, 5 misses, 0 errors. Hit ratio 154/159 = 96.9%. Mean cache operation 1.1 ms |
| Redis | `bazaar_redis_up` 1. Keyspace counters moved by the same 154 hits and 5 misses. Lifetime `GET` latency in Redis is about 5 µs at p50 and 98 µs at p99. That histogram is not limited to this test |
| Hikari acquire | p95 about 0.95 ms on catalog, inventory, order, and payment. Mean acquire about 0.13–0.16 ms |
| Hikari connections | max 10, active 0 and pending 0 at the snapshots before and after the run |
| Connection hold (usage) | mean about 2.0 ms inventory, 0.55 ms catalog, 0.53 ms order, 0.34 ms payment. Acquire counts are much larger than the k6 request count because other pool users share the counter |
| Kafka lag | application lag and exporter lag were 0 at the snapshots. Outbox pending was 0 |
| Deadlocks | 0 on every database |
| CPU and memory | During reads, catalog was the busiest Java process at about 6.6% CPU and 382 MiB of its 512 MiB limit. Other Java services sat around 290–350 MiB. One earlier sample during saga traffic showed Kafka at 57% CPU and 504 MiB of 768 MiB. A later sample in the same phase was about 2.5% CPU. Postgres stayed near 145 MiB |

Per-statement SQL latency is not available. `pg_stat_statements` is not installed. The pool acquire and connection-hold times are the database timing this run could record.

## Business outcome

Shirt stock before this milestone was available 4, reserved 0. Lamp stayed at available 1, reserved 0. Two `SUCCESS` checkouts of quantity 1 left the shirt at available 2, reserved 0.

On the measured full run, while 3 shirts were still available, 4 sequential `FAILURE` checkouts ended `PAYMENT_FAILED`. The following 4 concurrent accepts also returned HTTP 201, then 3 ended `PAYMENT_FAILED` and 1 ended `FAILED`. The failed order is an inventory reservation rejection (`onReservationFailed` while the order was still `PENDING_INVENTORY`). The HTTP accept does not wait for that reservation. Four overlapping reservations against 3 units is enough to reject one. Reserved stock was 0 again after the saga finished. The same 1-of-4 rejection happened on the earlier full pass.

The short timing run happened after the shirt was already at 2. It added 6 `PAYMENT_FAILED` orders and 2 `FAILED` orders. Stock stayed at 2 available and 0 reserved. That run is the checkout latency split above, not a second capacity test.

## What this shows

Search is the slowest read in this set, and it is still under 25 ms at p99 for this client and this pause. Nothing in the JVM, the pool, or Kafka lag was saturated at 1.7 reads per second.

Two ceilings show up before CPU does. The gateway limits one client address to 120 reads and 30 writes a minute, so a larger k6 run from this container would measure 429 responses rather than catalog capacity. Checkout returns 201 before the reservation is held, so concurrent accepts can exceed the stock that is free at that moment and one of those orders then ends `FAILED`.

No code was changed to chase these numbers.

## Errors and limitations

- The laptop and the 7.6 GiB Docker VM bound the result. Do not treat any request rate here as a production capacity.
- Read and write rates were kept under the gateway limit on purpose. Behavior above that limit was not measured.
- Checkout percentile rows have 4 or 8 samples. One success HTTP sample is not a distribution.
- Saga time is the order row's `updated_at - created_at` for two confirmed orders, not a k6 trend.
- SQL statement latency was not measured.
- Kafka lag was sampled at the ends of the run, not as a peak gauge during the burst. The 57% Kafka CPU reading is one `docker stats` sample.
- Flash Sale traffic was not load-tested. The route is absent until that milestone exists.
- In-process counters reset when a process restarts. The deltas above are for processes that stayed up through the run.
- The smoke scripts `catalog.js` and `checkout-failure.js` are unchanged and are still not this baseline.

Raw k6 summaries are in `infrastructure/k6/results/`.
