# Order Service

Runs checkout. The price comes from the catalog. The client cannot set it. Stock is reserved in the inventory service, then a mock payment is created in the payment service. Nothing here talks to a bank or reads another service's database.

Checkout returns `PENDING_INVENTORY` and finishes in the background. The order service is the saga coordinator. It sends inventory and payment commands from its outbox and moves the order only when the matching event arrives. States are `PENDING_INVENTORY`, `PENDING_PAYMENT`, `PENDING_INVENTORY_COMMIT`, `COMPENSATING`, `CONFIRMED`, `PAYMENT_FAILED`, `CANCELLED`, and `FAILED`. A stuck step is sent again. After three failed commits, or when the step times out, a successful payment is refunded. A stopped Kafka broker does not fail readiness. A message that still fails after bounded retries is copied to `bazaar.dlq` with its event id and error. An outbox row that cannot be published becomes `DEAD` instead of retrying forever. Replay steps are in `contracts/events/README.md`. The contract is in `contracts/events/README.md`.

`outcome` is `SUCCESS` or `FAILURE`. That chooses the mock payment result. A repeated checkout with the same `Idempotency-Key` and the same items returns the original order.

## Requirements

- JDK 25 on `JAVA_HOME`
- Maven Wrapper at the repository root
- PostgreSQL for a local run. `./mvnw test` starts its own database with Testcontainers and stubs the other services.

## Build and test

From the repository root:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
./mvnw -pl services/order-service -am test
```

## Run

```bash
./mvnw -pl services/order-service -am spring-boot:run
```

The process listens on port 8084 unless `SERVER_PORT` is set. A local run needs `order_db`, Keycloak, and the catalog, inventory, and payment processes.

## Endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/actuator/health` | Public | Process health |
| GET | `/api/v1/orders/info` | Public | Service name, version, and status |
| POST | `/api/v1/orders/checkout` | Bearer JWT | Reserve stock, create the order, and simulate payment. Requires `Idempotency-Key` |
| GET | `/api/v1/orders` | Bearer JWT | The caller's orders as a customer. An admin sees the latest 20 |
| GET | `/api/v1/orders/sales` | Seller | This seller's lines only, with search and pages. An admin token without `SELLER` is refused |
| GET | `/api/v1/orders/{id}` | Buyer or admin | Order, item price snapshot, and status history |
| POST | `/api/v1/orders/{id}/cancel` | Buyer or admin | Cancel a `PENDING_PAYMENT` order and release its holds |

Statuses used here are `PENDING_PAYMENT`, `CONFIRMED`, `PAYMENT_FAILED`, and `CANCELLED`.

If payment cannot be reached after the order is stored, the response is 503. Send the same key again. Holds that were released after a stock rejection need a new key. A hold that nobody completes expires in the inventory service.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8084` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/order_db` | This service's database |
| `DB_USERNAME` | `order_user` | Database user |
| `DB_PASSWORD` | `order_local` | Local database password |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8088/realms/bazaar` | Expected `iss` claim |
| `KEYCLOAK_JWK_SET_URI` | issuer certs URL | Signing keys |
| `CATALOG_SERVICE_URL` | `http://localhost:8082` | Catalog base URL |
| `INVENTORY_SERVICE_URL` | `http://localhost:8083` | Inventory base URL |
| `PAYMENT_SERVICE_URL` | `http://localhost:8085` | Payment base URL |

## Versions

Java 25 and Spring Boot 4.1.2 are pinned in the parent POM. Schema changes go through Flyway. Hibernate `ddl-auto` is `none`.
