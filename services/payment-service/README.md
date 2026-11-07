# Payment Service

Stores mock payments and the attempts made against them. It does not call a bank or the order service. The order id and amount are recorded here so a later checkout can attach them to an order.

A repeated request with the same `Idempotency-Key` returns the original payment. An order can have only one `PENDING` or `SUCCEEDED` payment. A `FAILED` or `CANCELLED` payment leaves the order free for another attempt.

A change to `SUCCEEDED`, `FAILED`, or `CANCELLED` writes `payment.succeeded`, `payment.failed`, or `payment.cancelled` in the same transaction. Repeating that call does not write a second row. The poller publishes `bazaar.payment.events`, and `processed_events` keeps one row per `eventId`. Readiness does not include Kafka.

## Requirements

- JDK 25 on `JAVA_HOME`
- Maven Wrapper at the repository root
- PostgreSQL for a local run. `./mvnw test` starts its own database with Testcontainers.

## Build and test

From the repository root:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
./mvnw -pl services/payment-service -am test
```

## Run

```bash
./mvnw -pl services/payment-service -am spring-boot:run
```

The process listens on port 8085 unless `SERVER_PORT` is set. A local run needs `payment_db` and a Keycloak issuer. Compose provides both.

## Endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/actuator/health` | Public | Process health |
| GET | `/api/v1/payments/info` | Public | Service name, version, and status |
| POST | `/api/v1/payments` | Bearer JWT | Create a `PENDING` payment. Requires `Idempotency-Key` |
| GET | `/api/v1/payments/{id}` | Payer or admin | Read the payment and its attempts |
| POST | `/api/v1/payments/{id}/simulate-success` | Payer or admin | Mark a pending payment `SUCCEEDED` |
| POST | `/api/v1/payments/{id}/simulate-failure` | Payer or admin | Mark a pending payment `FAILED` |
| POST | `/api/v1/payments/{id}/cancel` | Payer or admin | Mark a pending payment `CANCELLED` |

The payer id comes from the token. Repeating success, failure, or cancel on a payment that already has that status returns the same payment and does not add another attempt.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8085` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/payment_db` | This service's database |
| `DB_USERNAME` | `payment_user` | Database user |
| `DB_PASSWORD` | `payment_local` | Local database password |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8088/realms/bazaar` | Expected `iss` claim |
| `KEYCLOAK_JWK_SET_URI` | issuer certs URL | Signing keys |

## Versions

Java 25 and Spring Boot 4.1.2 are pinned in the parent POM. Schema changes go through Flyway. Hibernate `ddl-auto` is `none`.
