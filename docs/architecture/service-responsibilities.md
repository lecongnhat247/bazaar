# Service responsibilities

Phase 1 responsibilities are operational. Each service starts, reports health, and identifies itself. Domain behavior listed under later phases is a boundary, not an implementation commitment for this milestone.

## API Gateway

| | |
| --- | --- |
| Technology | Spring Cloud Gateway on Spring Boot |
| Port | 8080 |
| Data | None |

Phase 1:

- Accept external HTTP requests.
- Route `/api/v1/*` prefixes to the service URLs in the environment.
- Apply CORS for origins in `CORS_ALLOWED_ORIGINS`.
- Expose Actuator health, including liveness and readiness.
- Log in a structured console format and shut down gracefully.

The gateway does not validate tokens, limit rates, or implement marketplace rules. It forwards the `Authorization` header. Each business service checks the JWT.

## Identity Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8081 as a local process |
| Data | PostgreSQL `identity_db`. Keycloak issues tokens and keeps its own database. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/identity/info`. Both stay public.

Phase 2 stores a profile for the Keycloak subject and the application roles `CUSTOMER`, `SELLER`, `ADMIN`, and `OPERATOR`. `GET /api/v1/identity/me` requires a bearer token. Login and password storage stay in Keycloak.

## Catalog Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8082 as a local process |
| Data | PostgreSQL `catalog_db`. JSONB is the planned store for category-specific attributes. See [ADR-006](../adr/ADR-006-catalog-postgresql.md). |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/catalog/info`. Both stay public.

Phase 2 stores categories, products, image metadata, and flexible attributes in JSONB. Customers with a token see `ACTIVE` products only. A seller creates a product as `DRAFT` and publishes it with a status change. The seller id comes from the token. Stock quantities stay in inventory. There is no product variant in this milestone.

## Inventory Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8083 as a local process |
| Data | PostgreSQL `inventory_db`. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/inventory/info`. The info path stays public. Other paths require a JWT. Stock tables are a later milestone.

Later, this service owns stock quantities, reservations, reservation expiry, and movement history. Stock changes must be atomic in the database.

## Order Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8084 as a local process |
| Data | PostgreSQL `order_db`. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/orders/info`. The info path stays public. Other paths require a JWT. Order tables are a later milestone.

Later, this service owns order creation, retrieval, cancellation, and explicit state transitions: `PENDING`, `PENDING_PAYMENT`, `CONFIRMED`, `PROCESSING`, `SHIPPED`, `DELIVERED`, `CANCELLED`, and `PAYMENT_FAILED`.

## Payment Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8085 as a local process |
| Data | PostgreSQL `payment_db`. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/payments/info`. The info path stays public. Other paths require a JWT. Payment tables are a later milestone.

Later, this service simulates payment processing and stores payment attempts. An idempotency key must prevent a repeated request from creating a second successful charge. A real payment provider is out of scope until a later decision.

## Storefront

| | |
| --- | --- |
| Technology | Next.js, React, TypeScript |
| Port | 3000 |
| Data | None |

Phase 1 serves a responsive landing page, a simple navigation layout, and a gateway connectivity check with loading, success, and error states.

The storefront does not own accounts, products, a cart, or checkout.

## Ownership rules

- Each future database belongs to exactly one service. Tables are not shared.
- The gateway and the storefront do not own a database.
- Phase 1 services do not call each other. Only the gateway holds downstream URLs.
- A later checkout flow may call inventory and payment from the order service, or move that coordination to events. That choice is deferred. [ADR-003](../adr/ADR-003-rest-communication.md) keeps Phase 1 on synchronous REST.

## Later services

These services are not part of Phase 1 and have no folders yet.

| Service | Planned stack | Planned data |
| --- | --- | --- |
| Notification | NestJS | Its own store, plus WebSocket connections to browsers |
| Recommendation | Python and FastAPI | Read models derived from events |
| Analytics | C# and .NET | SQL Server read models |

Kafka remains the event bus between services when those flows exist. WebSocket is for browser delivery, not a substitute for the bus.
