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
| `bazaar-notification-service` | `node:24.22.2-bookworm-slim` | 8086 | 8080 |
| `postgres:17.11-alpine` | PostgreSQL 17.11 | 5432 | 5432 |
| `quay.io/keycloak/keycloak:26.8.1` | Keycloak 26.8.1 | 8088 | 8080 |
| `apache/kafka:4.1.1` | Kafka 4.1.1 in KRaft | 9092 | 9092 and 19092 |
| `redis:8.10.3-alpine` | Redis 8.10.3 | 6379 | 6379 |

Java images are built with `eclipse-temurin:25.0.5_8-jdk-noble` and run on the JRE image. The runtime user is `bazaar` (uid 1001). The storefront runs as the image's `node` user. `curl` is installed in the Java runtime only so the readiness probe can run. It is not used to build the application.

Addresses are set in `docker-compose.yml`. Nothing environment-specific is copied into the image. Database and Keycloak passwords in that file are local development values.

Inside the network the gateway calls `http://<service>:8080`. The storefront server reads `API_GATEWAY_URL=http://api-gateway:8080` and `NOTIFICATION_URL=http://notification-service:8080`. The browser opens the socket at `NOTIFICATION_PUBLIC_URL` (`http://localhost:8086`). Each Java container is limited to 512 MB, Postgres to 512 MB, Keycloak to 768 MB, Kafka to 768 MB, the topic init container to 256 MB, Redis to 128 MB, the notification database init to 128 MB, and the storefront and notification service to 384 MB each. Stop the kind cluster before starting Compose. The Docker Desktop VM is about 7.6 GiB.

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

`docker compose down` removes the containers and the `bazaar` network. Images stay on the machine. The named volume `bazaar-postgres-data` also stays, so the databases survive a restart. `bazaar-kafka-data` keeps the broker log. `docker compose down -v` deletes both volumes. The init script runs only when the Postgres volume is empty, so a password change in the init script needs a new volume. `notification-db-init` creates `notification_user` and `notification_db` when that volume already existed before the notification service.

## Kafka

One KRaft process combines the broker and the controller. Clients inside Compose use `kafka:19092`. A process on the host uses `localhost:9092`. Catalog, inventory, order, and payment read `KAFKA_BOOTSTRAP_SERVERS`. None of them wait for Kafka before serving HTTP.

`kafka-init` creates the event topics and two command topics after the broker is healthy: `bazaar.order.events`, `bazaar.inventory.events`, `bazaar.payment.events`, `bazaar.catalog.events`, `bazaar.inventory.commands`, and `bazaar.payment.commands`. Each has 3 partitions and replication factor 1. The init container exits when the topics exist. List them with:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

The broker health check is its own command. Readiness stays `/actuator/health/readiness` and does not include Kafka. Broker status is `GET /actuator/health/kafka` on catalog, inventory, order, and payment.

Redis keeps a 64 MB cache with no disk snapshot. Catalog and the gateway read `REDIS_HOST`. Neither waits for Redis before serving HTTP, and readiness does not include Redis. A restart drops cached pages and rate-limit counters. PostgreSQL remains the catalog source of truth. The notification service can use the same Redis to share socket rooms across instances. Its notices stay in PostgreSQL.

The notification service reads `KAFKA_BROKERS` and does not wait for the broker before serving HTTP. `GET /health/ready` checks `notification_db` only. The browser socket is not proxied by the gateway.

## Postgres and Keycloak

One Postgres container holds `identity_db`, `catalog_db`, `inventory_db`, `order_db`, `payment_db`, `keycloak_db`, and `notification_db`. Each has its own user. Business services do not connect to `keycloak_db`. Flyway migrates the five Java business databases. The notification service creates its own table. Hibernate does not create tables.

Keycloak imports the `bazaar` realm from `infrastructure/keycloak/bazaar-realm.json` on startup. The realm roles are `CUSTOMER`, `SELLER`, `ADMIN`, and `OPERATOR`. Local users use the same names with passwords `customer_local`, `seller_local`, `admin_local`, and `operator_local`. The public client `bazaar-dev` allows the authorization code flow and, for local curl only, a direct access grant. There is no client secret.

Tokens are issued at `http://localhost:8088/realms/bazaar`. Services inside Compose load signing keys from `http://keycloak:8080`. A request to Keycloak for a customer token:

```bash
curl -s -X POST http://localhost:8088/realms/bazaar/protocol/openid-connect/token \
  -d "client_id=bazaar-dev" \
  -d "grant_type=password" \
  -d "username=customer" \
  -d "password=customer_local"
```

`GET /api/v1/identity/info` stays public. `GET /api/v1/identity/me` requires the access token. Product and category calls also go through the gateway: `/api/v1/products` and `/api/v1/categories` are forwarded to the catalog service. Inventory holds are under `/api/v1/inventory`. Mock payments are under `/api/v1/payments`. Checkout is `POST /api/v1/orders/checkout`. The order container calls catalog, inventory, and payment on the Compose network. The storefront signs customers in through Keycloak at `http://localhost:8088` and exchanges the code from inside the network. The gateway forwards the `Authorization` header and does not validate it.

On Windows, Compose 5.6 compares the build context path with the Dockerfile path and treats a lowercase drive letter as a different directory. From Git Bash, start in `D:\bazaar` so both paths use the same drive letter. If the build stops with `additional privileges requested` and the two paths differ only by that letter, that is the cause. Compose 5.7 grants the Dockerfile path; this machine is on Compose 5.6.1, and Docker is not upgraded in this milestone.

## Health checks

Java containers probe `http://127.0.0.1:8080/actuator/health/readiness`. The storefront probes its own root path with Node.js. That probe does not call the gateway, so a gateway failure does not restart the storefront. Compose still waits until the gateway is healthy before starting the storefront, so the first page load has a gateway to reach.

`restart: unless-stopped` restarts a container that exits. It does not restart a healthy container because another service fails.
