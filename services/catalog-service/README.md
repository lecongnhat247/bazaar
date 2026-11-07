# Catalog Service

Owns categories, products, image metadata, and flexible product attributes. Stock quantities belong to the inventory service. Keycloak issues tokens. This service checks the seller id from the token, not from the request body.

## Requirements

- JDK 25 on `JAVA_HOME`
- Maven Wrapper at the repository root
- PostgreSQL for a local run. `./mvnw test` starts its own database with Testcontainers.

## Build and test

From the repository root:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
./mvnw -pl services/catalog-service -am test
```

## Run

```bash
./mvnw -pl services/catalog-service -am spring-boot:run
```

The process listens on port 8082 unless `SERVER_PORT` is set. A local run needs `catalog_db` and a Keycloak issuer. Compose provides both.

## Endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/actuator/health` | Public | Process health |
| GET | `/api/v1/catalog/info` | Public | Service name, version, and status |
| GET | `/api/v1/categories` | Public | List categories |
| GET | `/api/v1/products` | Public | Search active products. A signed-in seller passes `mine=true` to see their own drafts |
| GET | `/api/v1/products/{id}` | Public | One active product. Drafts are visible to the owner and an admin |
| POST | `/api/v1/products` | Seller or admin | Create a draft. The seller id is the token subject |
| PUT | `/api/v1/products/{id}` | Owner or admin | Replace the editable fields. Status stays unchanged |
| PATCH | `/api/v1/products/{id}/status` | Owner or admin | Move among `DRAFT`, `ACTIVE`, `INACTIVE`, and `ARCHIVED` |

List parameters are `page`, `size` (max 50), `sort` (`createdAt`, `name`, `price`), `direction` (`asc` or `desc`), `q`, `categoryId`, `minPrice`, `maxPrice`, `status`, and `mine`. Customers always receive `ACTIVE` products. A new product starts as `DRAFT`. `ARCHIVED` is final.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8082` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/catalog_db` | This service's database |
| `DB_USERNAME` | `catalog_user` | Database user |
| `DB_PASSWORD` | `catalog_local` | Local database password |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8088/realms/bazaar` | Expected `iss` claim |
| `KEYCLOAK_JWK_SET_URI` | issuer certs URL | Signing keys |

## Versions

Java 25 and Spring Boot 4.1.2 are pinned in the parent POM. Schema changes go through Flyway. Hibernate `ddl-auto` is `none`.
