# Docker Compose

Compose runs the local stack on one Docker network named `bazaar`. Kubernetes uses the same application images with different configuration. Postgres and Keycloak run here for Phase 2 and are not part of the kind chart.

## Images

| Image | Base | Host port | Container port |
| --- | --- | --- | --- |
| `bazaar-storefront` | `node:24.22.2-bookworm-slim` | 3000 | 3000 |
| `bazaar-api-gateway` | `eclipse-temurin:25.0.5_8-jre-noble` | 8080 | 8080 |
| `bazaar-identity-service` | `eclipse-temurin:25.0.5_8-jre-noble` | 8081 | 8080 |
| `bazaar-catalog-service` | `eclipse-temurin:25.0.5_8-jre-noble` | 8082 | 8080 |
| `bazaar-inventory-service` | `eclipse-temurin:25.0.5_8-jre-noble` | 8083 | 8080 |
| `bazaar-order-service` | `eclipse-temurin:25.0.5_8-jre-noble` | 8084 | 8080 |
| `bazaar-payment-service` | `eclipse-temurin:25.0.5_8-jre-noble` | 8085 | 8080 |
| `postgres:17.11-alpine` | PostgreSQL 17.11 | 5432 | 5432 |
| `quay.io/keycloak/keycloak:26.8.1` | Keycloak 26.8.1 | 8088 | 8080 |

Java images are built with `eclipse-temurin:25.0.5_8-jdk-noble` and run on the JRE image. The runtime user is `bazaar` (uid 1001). The storefront runs as the image's `node` user. `curl` is installed in the Java runtime only so the readiness probe can run. It is not used to build the application.

Addresses are set in `docker-compose.yml`. Nothing environment-specific is copied into the image. Database and Keycloak passwords in that file are local development values.

Inside the network the gateway calls `http://<service>:8080`. The storefront server reads `API_GATEWAY_URL=http://api-gateway:8080`. Each Java container is limited to 512 MB, Postgres to 512 MB, Keycloak to 768 MB, and the storefront to 384 MB. Stop the kind cluster before starting Compose. The Docker Desktop VM is about 7.6 GiB.

## Start and stop

From the repository root, with Docker Desktop running and the kind cluster stopped:

```bash
docker compose up --build -d
docker compose ps
docker compose logs -f api-gateway
```

Open http://localhost:3000 for the storefront and http://localhost:8080/actuator/health for the gateway. The storefront status panel should report the gateway at `http://api-gateway:8080`.

```bash
docker compose down
```

`docker compose down` removes the containers and the `bazaar` network. Images stay on the machine. The named volume `bazaar-postgres-data` also stays, so the six databases survive a restart. `docker compose down -v` deletes that volume. The init script runs only when the volume is empty, so a password change in the init script needs a new volume.

## Postgres and Keycloak

One Postgres container holds six databases: `identity_db`, `catalog_db`, `inventory_db`, `order_db`, `payment_db`, and `keycloak_db`. Each has its own user. Business services do not connect to `keycloak_db`. Flyway migrates the five business databases. Hibernate does not create tables.

Keycloak imports the `bazaar` realm from `infrastructure/keycloak/bazaar-realm.json` on startup. The realm roles are `CUSTOMER`, `SELLER`, `ADMIN`, and `OPERATOR`. Local users use the same names with passwords `customer_local`, `seller_local`, `admin_local`, and `operator_local`. The public client `bazaar-dev` allows the authorization code flow and, for local curl only, a direct access grant. There is no client secret.

Tokens are issued at `http://localhost:8088/realms/bazaar`. Services inside Compose load signing keys from `http://keycloak:8080`. A request to Keycloak for a customer token:

```bash
curl -s -X POST http://localhost:8088/realms/bazaar/protocol/openid-connect/token \
  -d "client_id=bazaar-dev" \
  -d "grant_type=password" \
  -d "username=customer" \
  -d "password=customer_local"
```

`GET /api/v1/identity/info` stays public. `GET /api/v1/identity/me` requires the access token. The gateway forwards the `Authorization` header and does not validate it.

On Windows, Compose 5.6 compares the build context path with the Dockerfile path and treats a lowercase drive letter as a different directory. From Git Bash, start in `D:\bazaar` so both paths use the same drive letter. If the build stops with `additional privileges requested` and the two paths differ only by that letter, that is the cause. Compose 5.7 grants the Dockerfile path; this machine is on Compose 5.6.1, and Docker is not upgraded in this milestone.

## Health checks

Java containers probe `http://127.0.0.1:8080/actuator/health/readiness`. The storefront probes its own root path with Node.js. That probe does not call the gateway, so a gateway failure does not restart the storefront. Compose still waits until the gateway is healthy before starting the storefront, so the first page load has a gateway to reach.

`restart: unless-stopped` restarts a container that exits. It does not restart a healthy container because another service fails.
