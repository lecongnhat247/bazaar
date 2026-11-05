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

The gateway does not authenticate, limit rates, or implement marketplace rules.

## Identity Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8081 as a local process |
| Data | None in Phase 1. PostgreSQL in a later phase. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/identity/info`.

Later, this service owns customer registration, login, password hashing, JWT access tokens, refresh tokens, profiles, and the roles `CUSTOMER`, `SELLER`, and `ADMIN`.

## Catalog Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8082 as a local process |
| Data | None in Phase 1. MongoDB in a later phase. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/catalog/info`.

Later, this service owns products, categories, image metadata, search, and flexible category-specific attributes. Stock quantities stay out of the catalog.

## Inventory Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8083 as a local process |
| Data | None in Phase 1. PostgreSQL in a later phase. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/inventory/info`.

Later, this service owns stock quantities, reservations, reservation expiry, and movement history. Stock changes must be atomic in the database.

## Order Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8084 as a local process |
| Data | None in Phase 1. PostgreSQL in a later phase. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/orders/info`.

Later, this service owns order creation, retrieval, cancellation, and explicit state transitions: `PENDING`, `PENDING_PAYMENT`, `CONFIRMED`, `PROCESSING`, `SHIPPED`, `DELIVERED`, `CANCELLED`, and `PAYMENT_FAILED`.

## Payment Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8085 as a local process |
| Data | None in Phase 1. PostgreSQL in a later phase. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/payments/info`.

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
