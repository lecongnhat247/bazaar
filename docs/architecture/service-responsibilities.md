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

Phase 2 stores categories, products, image metadata, and flexible attributes in JSONB. A public product read returns `ACTIVE` products only. A seller creates a product as `DRAFT` and publishes it with a status change. The seller id comes from the token. Stock quantities stay in inventory. There is no product variant in this milestone.

A status change into or out of `ACTIVE`, and an update of an `ACTIVE` product, inserts `bazaar.catalog.events` into the catalog outbox in the same transaction. Creating or editing a draft does not. Readiness does not include Kafka.

## Inventory Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8083 as a local process |
| Data | PostgreSQL `inventory_db`. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/inventory/info`. Both stay public.

Phase 2 stores on-hand stock, holds, and movement history in `inventory_db`. A hold decrements available stock with one conditional SQL update, so concurrent checkouts cannot oversell. Holds expire and return stock. Committing a hold keeps the stock sold and clears the reserved count. There is no foreign key to the catalog.

A new hold, a release, a commit, and an expiry each insert one `bazaar.inventory.events` row in that same transaction. Replaying an idempotency key does not insert another row. A rejected hold has no row to publish. Readiness does not include Kafka.

## Order Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8084 as a local process |
| Data | PostgreSQL `order_db`. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/orders/info`. Both stay public.

Phase 2 checkout reads the catalog price, reserves stock, stores a `PENDING_PAYMENT` order, and asks payment to simulate success or failure. Success confirms the order and commits the reservation. Failure marks `PAYMENT_FAILED` and releases the reservation. A customer can cancel only while payment is still pending. The service calls the other three over HTTP and does not read their databases. There is no shipping status in this milestone.

Checkout still returns the order from the same HTTP call. The insert and each later status change also write `bazaar.order.events` to the order outbox in that transaction. The service consumes the topic and applies each `eventId` once. Readiness does not include the broker, so a Kafka outage does not take checkout or product reads down.

## Payment Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8085 as a local process |
| Data | PostgreSQL `payment_db`. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/payments/info`. Both stay public.

Phase 2 stores mock payments and payment attempts. A payment is bound to an order id and an amount. The same idempotency key cannot create a second payment, and an order cannot have two pending or successful payments. Success and failure are simulated. There is no card network and no call to the order service yet.

A real status change to `SUCCEEDED`, `FAILED`, or `CANCELLED` inserts one `bazaar.payment.events` row in that same transaction. Creating a pending payment does not. Readiness does not include Kafka.

## Storefront

| | |
| --- | --- |
| Technology | Next.js, React, TypeScript |
| Port | 3000 |
| Data | None |

Phase 1 serves a responsive landing page and a gateway connectivity check.

Phase 2 adds the customer shop: product list and detail, a browser cart, Keycloak sign-in, checkout, payment simulation, and order history. The browser calls the storefront. The storefront calls the gateway with the access token from an HTTP-only cookie. Product prices in the cart are only a preview.

## Ownership rules

- Each future database belongs to exactly one service. Tables are not shared.
- The gateway and the storefront do not own a database.
- The gateway does not call a database. It forwards the caller's token.
- Checkout is synchronous REST. The order service calls catalog, inventory, and payment with the caller's token. It does not share their tables. [ADR-003](../adr/ADR-003-rest-communication.md) still defers events and a Saga.

## Later services

These services are not part of Phase 1 and have no folders yet.

| Service | Planned stack | Planned data |
| --- | --- | --- |
| Notification | NestJS | Its own store, plus WebSocket connections to browsers |
| Recommendation | Python and FastAPI | Read models derived from events |
| Analytics | C# and .NET | SQL Server read models |

Kafka remains the event bus between services when those flows exist. WebSocket is for browser delivery, not a substitute for the bus.
