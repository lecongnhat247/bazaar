# Inventory Service

Spring Boot service for BAZAAR inventory. Stock reservation is a later milestone. This process owns `inventory_db`, validates Keycloak JWTs, and leaves health and `/api/v1/inventory/info` public.

## Requirements

- JDK 25 on `JAVA_HOME`
- Maven Wrapper at the repository root

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

The process listens on port 8083 unless `SERVER_PORT` is set.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/actuator/health` | Process health |
| GET | `/actuator/health/liveness` | Liveness probe |
| GET | `/actuator/health/readiness` | Readiness probe |
| GET | `/api/v1/inventory/info` | Service name, version, and status |

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8083` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/inventory_db` | This service's database |
| `DB_USERNAME` | `inventory_user` | Database user |
| `DB_PASSWORD` | `inventory_local` | Local database password |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8088/realms/bazaar` | Expected `iss` claim |
| `KEYCLOAK_JWK_SET_URI` | issuer certs URL | Signing keys |

## Versions

Java 25 and Spring Boot 4.1.2 are pinned in the parent POM.
