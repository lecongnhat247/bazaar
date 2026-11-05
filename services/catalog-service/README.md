# Catalog Service

Spring Boot skeleton for the BAZAAR catalog bounded context. Product management is later work. This process exposes health and a service info endpoint.

## Requirements

- JDK 25 on `JAVA_HOME`
- Maven Wrapper at the repository root

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

The process listens on port 8082 unless `SERVER_PORT` is set.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/actuator/health` | Process health |
| GET | `/actuator/health/liveness` | Liveness probe |
| GET | `/actuator/health/readiness` | Readiness probe |
| GET | `/api/v1/catalog/info` | Service name, version, and status |

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8082` | HTTP port |

## Versions

Java 25 and Spring Boot 4.1.2 are pinned in the parent POM.
