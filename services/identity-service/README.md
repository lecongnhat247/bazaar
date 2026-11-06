# Identity Service

Owns the local user profile for a Keycloak account. Keycloak issues tokens. This service validates them and stores the subject, email, display name, and application roles.

## Requirements

- JDK 25 on `JAVA_HOME`
- Maven Wrapper at the repository root
- PostgreSQL for a local run. `./mvnw test` starts its own database with Testcontainers.

## Build and test

From the repository root:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.5.0.3"
./mvnw -pl services/identity-service -am test
```

## Run

```bash
./mvnw -pl services/identity-service -am spring-boot:run
```

The process listens on port 8081 unless `SERVER_PORT` is set. A local run needs `identity_db` and a Keycloak issuer. Compose provides both.

## Endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/actuator/health` | Public | Process health |
| GET | `/actuator/health/liveness` | Public | Liveness probe |
| GET | `/actuator/health/readiness` | Public | Readiness probe, including the database |
| GET | `/api/v1/identity/info` | Public | Service name, version, and status |
| GET | `/api/v1/identity/me` | Bearer JWT | Create or return the caller's profile |
| PATCH | `/api/v1/identity/me` | Bearer JWT | Change `displayName` |
| GET | `/api/v1/identity/users/{id}` | Bearer JWT | Caller, or an `ADMIN`, reads one profile |

The first `GET /me` inserts the profile. A second call returns the same id. Roles copied from the token are only `CUSTOMER`, `SELLER`, `ADMIN`, and `OPERATOR`.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/identity_db` | Profile database |
| `DB_USERNAME` | `identity_user` | Database user |
| `DB_PASSWORD` | `identity_local` | Local database password |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8088/realms/bazaar` | Expected `iss` claim |
| `KEYCLOAK_JWK_SET_URI` | issuer `/protocol/openid-connect/certs` | Signing keys |

## Versions

Java 25 and Spring Boot 4.1.2 are pinned in the parent POM. Schema changes go through Flyway. Hibernate `ddl-auto` is `none`.
