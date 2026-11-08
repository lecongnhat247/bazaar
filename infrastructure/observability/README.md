# Observability

Prometheus scrapes the Java `/actuator/prometheus` endpoints and the notification `/metrics` endpoint. Grafana on http://localhost:3001 reads that Prometheus. Anonymous viewers can open the dashboards. The local admin password is `grafana_local`.

The scrape targets are the API gateway, the five Java business services, the notification service, and one exporter each for PostgreSQL, Kafka, and Redis. Readiness does not include Prometheus, Grafana, Kafka, or Redis. A monitoring container can be down while checkout still runs.

Traces use the OpenTelemetry bridge already managed by Spring Boot 4.1.2. Sampling is `TRACING_SAMPLE_PROBABILITY`, default `1.0` on this laptop. There is no trace database. A trace is verified by the same `traceId` in the JSON logs of the services that handled the request. The storefront forwards an incoming `traceparent` and `X-Correlation-Id` to the gateway. Order calls to the catalog copy that correlation id. An outbox row stores the W3C `traceparent` from the request that created it, and the publisher restores that context before it sends to Kafka. Consumers then continue the same trace. Old outbox rows have a null `traceparent` and start a new producer span.

Logs are JSON. Java services use the ECS format and add `service.environment` from `BAZAAR_ENV` (default `local`), plus `traceId`, `spanId`, `correlationId`, and `eventId` when the request or record has them. The notification service writes the same fields. Do not log tokens, passwords, or payment secrets. Metric labels are service, status, result, and outcome. They are never a customer id or an order id.

## Dashboards

| Dashboard | What a panel means |
| --- | --- |
| Platform Overview | Targets that answered, HTTP throughput, 5xx rate, active sagas, and process memory. |
| API Gateway | Status mix, p50/p95 latency, and 429 responses from the rate limit. |
| JVM Services | Heap, non-heap, garbage collections, live threads, and process CPU. |
| Database | Hikari active, idle, pending, acquisition p95, and PostgreSQL deadlocks. |
| Kafka | Consumer lag, outbox pending and dead rows, processed records, retries, and dead letters. |
| Redis | Catalog ping, exporter status, memory, cache hit/miss/error, and command rate. |
| Checkout and Saga | Checkout attempts and results, terminal saga outcomes, active sagas, and payment results. |
| Inventory and Flash Sale | Reservation results and expirations. Flash Sale counters are not emitted yet. |

`bazaar_checkout_results{result="accepted"}` means the order was saved, not that it is confirmed. Confirmation is `bazaar_saga_completed{outcome="CONFIRMED"}`.

Redis memory and command rate come from the exporter. Cache hit and miss come from the catalog service. PostgreSQL remains the source of truth for stock, orders, and payments.

These numbers describe this laptop. They are not a production capacity claim.
