# BAZAAR

BAZAAR is a multi-vendor marketplace. Phase 1 is the deployable skeleton. Phase 2 is the customer purchase journey on Docker Compose: sign in, browse, cart, checkout, mock payment, and order history. Phase 3 records business facts in a service-local outbox and publishes them to a local Kafka broker. Checkout now runs as a saga coordinated by the order service. Catalog reads can come from Redis, and the gateway limits request rate there. Signed-in customers receive their own order and payment notices over a socket. A failed event is kept on a dead-letter topic or as a DEAD outbox row. A real payment provider is still later work.

## Phase 1

| Milestone | Scope | Status |
| --- | --- | --- |
| M0 | Environment assessment | Complete |
| M1 | Architecture docs, ADRs, repository files | Complete |
| M2 | Backend skeletons | Complete |
| M3 | Storefront skeleton | Complete |
| M4 | Docker Compose | Complete |
| M5 | kind and Helm | Complete |
| M6 | CI and deployment guides | Complete |

## Phase 2

| Milestone | Scope | Status |
| --- | --- | --- |
| M0 | Existing project audit | Complete |
| M1 | Database and security foundation | Complete |
| M2 | Catalog | Complete |
| M3 | Inventory | Complete |
| M4 | Payment | Complete |
| M5 | Order | Complete |
| M6 | Storefront | Complete |
| M7 | Integration | Complete |

## Phase 3

| Milestone | Scope | Status |
| --- | --- | --- |
| M0 | Phase 2 audit | Complete |
| M1 | Kafka foundation | Complete |
| M2 | Transactional outbox and idempotent consumers | Complete |
| M3 | Saga orchestration | Complete |
| M4 | Redis | Complete |
| M5 | Notifications and WebSocket | Complete |
| M6 | Reliability and testing | Complete |
| M7 | Integration | Complete |

## Phase 4

| Milestone | Scope | Status |
| --- | --- | --- |
| M0 | Phase 3 audit | Complete |
| M1 | Observability foundation | Complete |
| M2 | Performance baseline | Complete |
| M3 | Database concurrency | Complete |
| M4 | Flash sale | Complete |
| M5 | Resilience | Complete |
| M6 | Kubernetes production readiness | Complete |
| M7 | CI/CD and release | Complete |
| M8 | Chaos and capacity | Complete |

## Documentation

- [Architecture overview](docs/architecture/overview.md)
- [System context](docs/architecture/system-context.md)
- [Container diagram](docs/architecture/container-diagram.md)
- [Service responsibilities](docs/architecture/service-responsibilities.md)
- [Deployment architecture](docs/architecture/deployment-architecture.md)
- [Development setup](docs/development/setup.md)
- [Deployment overview](docs/deployment/overview.md)
- [Docker Compose](docs/deployment/docker-compose.md)
- [Phase 4 M2 performance baseline](docs/performance/m2-baseline.md)
- [Phase 4 M3 concurrency](docs/performance/m3-concurrency.md)
- [Phase 4 M4 flash sale](docs/performance/m4-flash-sale.md)
- [Phase 4 M5 resilience](docs/performance/m5-resilience.md)
- [Phase 4 M8 chaos and capacity](docs/performance/m8-chaos.md)
- [Observability](infrastructure/observability/README.md)
- [kind and Helm](docs/deployment/kubernetes-kind.md)
- [Remote Kubernetes](docs/deployment/remote-kubernetes.md)
- [GitHub Actions and GHCR](docs/deployment/github-actions.md)
- [Release](docs/deployment/release.md)
- [Local development](docs/development/local-development.md)
- [ADR-001 Monorepo](docs/adr/ADR-001-monorepo-structure.md)
- [ADR-002 Java and Spring Boot](docs/adr/ADR-002-java-and-spring-boot.md)
- [ADR-003 REST communication](docs/adr/ADR-003-rest-communication.md)
- [ADR-004 Docker and Kubernetes](docs/adr/ADR-004-docker-and-kubernetes.md)
- [ADR-005 Database ownership](docs/adr/ADR-005-database-ownership.md)
- [ADR-006 Catalog on PostgreSQL](docs/adr/ADR-006-catalog-postgresql.md)
- [ADR-007 Kafka in KRaft](docs/adr/ADR-007-kafka-kraft.md)

## Planned applications

| Application | Path | Host port when published | In-container port |
| --- | --- | --- | --- |
| Storefront | `apps/storefront` | 3000 | 3000 |
| API Gateway | `services/api-gateway` | 8080 | 8080 |
| Identity | `services/identity-service` | 8081 | 8080 |
| Catalog | `services/catalog-service` | 8082 | 8080 |
| Inventory | `services/inventory-service` | 8083 | 8080 |
| Order | `services/order-service` | 8084 | 8080 |
| Payment | `services/payment-service` | 8085 | 8080 |
| Notification | `services/notification-service` | 8086 | 8080 |

Inside Compose and Kubernetes every backend container listens on port 8080. The host ports above are for local processes and published Compose ports. Service-to-service calls use DNS names and port 8080.

## Configuration

Copy [`.env.example`](.env.example) to `.env` for a local process. `.env` is gitignored. Database and Keycloak passwords in that example are for the local machine only.

Local Java builds need `JAVA_HOME` pointed at the installed JDK 25 for that shell. The operating-system environment was left unchanged. See the [setup guide](docs/development/setup.md).

## Version baseline

Backend versions are pinned in the parent POM and the Maven Wrapper. The Next.js version is pinned in M3.

| Component | Selected version |
| --- | --- |
| Java | 25 (host: Oracle JDK 25.0.5.0.3; images: Eclipse Temurin 25) |
| Spring Boot | 4.1.2 |
| Spring Cloud | 2025.1.4 (Gateway 5.0.4) |
| Node.js / npm | 24.22.1 / 11.19.1 |
| Next.js / React | 16.4.5 / 19.3.0, pinned by the storefront lockfile |
| Maven | 3.10.0 through the Maven Wrapper |
| Docker Engine / Compose | 29.9.2 / 5.6.1 |
| kind / Kubernetes | 0.33.0 / 1.37.0 |
| Helm | 4.3.1 |
| kubectl for the cluster | 1.37.2 inside WSL Ubuntu |
| Kafka | Broker image `apache/kafka:4.1.1` in KRaft. Spring Kafka 4.1.2 and Kafka clients 4.2.2 come from Spring Boot 4.1.2 |

## Repository layout

Directories that would otherwise be empty are created in the milestone that fills them.

```text
bazaar/
├── .github/workflows/
├── apps/
│   └── storefront/
├── docs/
├── infrastructure/
│   ├── kind/
│   ├── helm/bazaar/
│   ├── postgres/
│   ├── keycloak/
│   └── scripts/
├── services/
│   ├── api-gateway/
│   ├── identity-service/
│   ├── catalog-service/
│   ├── inventory-service/
│   ├── order-service/
│   └── payment-service/
├── mvnw
├── mvnw.cmd
├── pom.xml
└── README.md
```

Contract files under `contracts/` are added when an API or event schema exists.

## Backend

From the repository root, with `JAVA_HOME` pointed at JDK 25:

```bash
./mvnw test
./mvnw -pl services/identity-service -am spring-boot:run
```

The parent project skips `spring-boot:run` because it has no main class. Each service turns that skip off, so the command above starts only the selected service.

Each service README lists its port, endpoints, and the command to build that module alone.

## Docker Compose

From the repository root, with Docker Desktop running:

```bash
docker compose up --build -d
```

The storefront is at http://localhost:3000 and the gateway at http://localhost:8080. Stop the stack with `docker compose down`. Details are in the [Compose guide](docs/deployment/docker-compose.md).

## kind and Helm

From WSL Ubuntu, with Compose stopped:

```bash
bash infrastructure/scripts/create-cluster.sh
bash infrastructure/scripts/load-images.sh
bash infrastructure/scripts/install.sh
```

Open the storefront and gateway with `kubectl port-forward`. Details are in the [kind guide](docs/deployment/kubernetes-kind.md). Stop the cluster with `bash infrastructure/scripts/delete-cluster.sh`.

## GitHub Actions

Pull requests run tests, the storefront build, and Helm lint, and they build images without pushing them. A push to `main` or a `v*` tag publishes images to GHCR with `GITHUB_TOKEN`. Details are in the [Actions guide](docs/deployment/github-actions.md). A remote cluster uses the same chart with different image values. That path is documented in [remote Kubernetes](docs/deployment/remote-kubernetes.md) and is not provisioned.
