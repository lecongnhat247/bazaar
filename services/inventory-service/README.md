# Inventory Service

Owns on-hand stock, reservations, and stock movements for a product id. It does not read the catalog database and does not store prices. Keycloak issues tokens.

Available stock changes in one conditional SQL update: `available >= quantity`. PostgreSQL locks that row for the update, so two reservations cannot both take the last unit. The service does not read the row and then write it back.

## Requirements

- JDK 25 on `JAVA_HOME`
- Maven Wrapper at the repository root
- PostgreSQL for a local run. `./mvnw test` starts its own database with Testcontainers.

## Build and test

From the repository root:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
./mvnw -pl services/inventory-service -am test
```

## Run

```bash
./mvnw -pl services/inventory-service -am spring-boot:run
```

The process listens on port 8083 unless `SERVER_PORT` is set. A local run needs `inventory_db` and a Keycloak issuer. Compose provides both.

## Endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/actuator/health` | Public | Process health |
| GET | `/api/v1/inventory/info` | Public | Service name, version, and status |
| GET | `/api/v1/inventory/products/{productId}` | Bearer JWT | Available and reserved quantities |
| PUT | `/api/v1/inventory/products/{productId}` | Seller owner or admin | Set on-hand stock. The seller id comes from the token |
| POST | `/api/v1/inventory/reservations` | Bearer JWT | Hold stock. Requires `Idempotency-Key` |
| POST | `/api/v1/inventory/reservations/{id}/release` | Requester or admin | Return the hold to available stock |
| POST | `/api/v1/inventory/reservations/{id}/commit` | Requester or admin | Keep the stock sold and clear the hold |

A new hold lasts 15 minutes (`INVENTORY_RESERVATION_TTL`). Expired holds return stock on the next inventory call and on a one-minute sweep. Repeating the same reserve key, release, or commit returns the current result. A different request body with the same key is rejected. `ARCHIVED` catalog products are not this service's concern.

Hold states are `HELD`, `COMMITTED`, `RELEASED`, and `EXPIRED`.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8083` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/inventory_db` | This service's database |
| `DB_USERNAME` | `inventory_user` | Database user |
| `DB_PASSWORD` | `inventory_local` | Local database password |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8088/realms/bazaar` | Expected `iss` claim |
| `KEYCLOAK_JWK_SET_URI` | issuer certs URL | Signing keys |
| `INVENTORY_RESERVATION_TTL` | `PT15M` | How long a hold lasts |

## Versions

Java 25 and Spring Boot 4.1.2 are pinned in the parent POM. Schema changes go through Flyway. Hibernate `ddl-auto` is `none`.
