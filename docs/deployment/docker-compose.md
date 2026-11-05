# Docker Compose

Compose runs the Phase 1 skeleton on one Docker network named `bazaar`. It is the local container workflow. Kubernetes uses the same images with different configuration.

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

Java images are built with `eclipse-temurin:25.0.5_8-jdk-noble` and run on the JRE image. The runtime user is `bazaar` (uid 1001). The storefront runs as the image's `node` user. `curl` is installed in the Java runtime only so the readiness probe can run. It is not used to build the application.

Addresses are set in `docker-compose.yml`. Nothing environment-specific is copied into the image. Phase 1 has no secrets.

Inside the network the gateway calls `http://<service>:8080`. The storefront server reads `API_GATEWAY_URL=http://api-gateway:8080`. Each Java container is limited to 512 MB, and the storefront to 384 MB, so the heap stays inside the Docker Desktop VM.

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

`docker compose down` removes the containers and the `bazaar` network. Images stay on the machine.

On Windows, Compose 5.6 compares the build context path with the Dockerfile path and treats a lowercase drive letter as a different directory. From Git Bash, start in `D:\bazaar` so both paths use the same drive letter. If the build stops with `additional privileges requested` and the two paths differ only by that letter, that is the cause. Compose 5.7 grants the Dockerfile path; this machine is on Compose 5.6.1, and Docker is not upgraded in this milestone.

## Health checks

Java containers probe `http://127.0.0.1:8080/actuator/health/readiness`. The storefront probes its own root path with Node.js. That probe does not call the gateway, so a gateway failure does not restart the storefront. Compose still waits until the gateway is healthy before starting the storefront, so the first page load has a gateway to reach.

`restart: unless-stopped` restarts a container that exits. It does not restart a healthy container because another service fails.
