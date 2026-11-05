# API Gateway

Spring Cloud Gateway entry point for BAZAAR. It routes `/api/v1/*` requests to the backend services, applies CORS, and exposes its own health endpoint. It does not contain marketplace business logic.

## Requirements

- JDK 25 on `JAVA_HOME`
- Maven Wrapper at the repository root

## Build and test

From the repository root:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
./mvnw -pl services/api-gateway -am test
```

## Run

```bash
./mvnw -pl services/api-gateway -am spring-boot:run
```

The process listens on port 8080 unless `SERVER_PORT` is set.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/actuator/health` | Gateway health |
| GET | `/actuator/health/liveness` | Liveness probe |
| GET | `/actuator/health/readiness` | Readiness probe |
| GET | `/api/v1/identity/**` | Forwarded to the identity service |
| GET | `/api/v1/catalog/**` | Forwarded to the catalog service |
| GET | `/api/v1/inventory/**` | Forwarded to the inventory service |
| GET | `/api/v1/orders/**` | Forwarded to the order service |
| GET | `/api/v1/payments/**` | Forwarded to the payment service |

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Browser origin allowed to call the gateway |
| `IDENTITY_SERVICE_URL` | `http://localhost:8081` | Identity base URL |
| `CATALOG_SERVICE_URL` | `http://localhost:8082` | Catalog base URL |
| `INVENTORY_SERVICE_URL` | `http://localhost:8083` | Inventory base URL |
| `ORDER_SERVICE_URL` | `http://localhost:8084` | Order base URL |
| `PAYMENT_SERVICE_URL` | `http://localhost:8085` | Payment base URL |

Inside Compose and Kubernetes the service URLs use DNS names on port 8080. See the root `.env.example`.

## Versions

Java 25, Spring Boot 4.1.2, and Spring Cloud 2025.1.4 are pinned in the parent POM. The gateway starter is `spring-cloud-starter-gateway-server-webflux`.
