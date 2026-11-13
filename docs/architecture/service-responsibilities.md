# Service responsibilities

Phase 1 responsibilities are operational. Each service starts, reports health, and identifies itself. Domain behavior listed under later phases is a boundary, not an implementation commitment for this milestone.

## API Gateway

| | |
| --- | --- |
| Technology | Spring Cloud Gateway on Spring Boot |
| Port | 8080 |
| Data | Redis for rate-limit counters only. No database. |

Phase 1:

- Accept external HTTP requests.
- Route `/api/v1/*` prefixes to the service URLs in the environment.
- Apply CORS for origins in `CORS_ALLOWED_ORIGINS`.
- Expose Actuator health, including liveness and readiness.
- Log in a structured console format and shut down gracefully.

The gateway forwards the `Authorization` header and does not decide whether a token may call a service. Each business service checks the JWT. For rate limits, a signature-valid token is counted against a hash of its subject. A missing or invalid token is counted against a hash of the caller address. Reads and writes have separate limits, and each route group has its own counter. Redis is not the source of those decisions: if Redis does not answer, the gateway allows the request. Readiness does not include Redis.

## Identity Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8081 as a local process |
| Data | PostgreSQL `identity_db`. Keycloak issues tokens and keeps its own database. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/identity/info`. Both stay public.

Phase 2 stores a profile for the Keycloak subject and the application roles `CUSTOMER`, `SELLER`, `ADMIN`, and `OPERATOR`. `GET /api/v1/identity/me` requires a bearer token. Login and password storage stay in Keycloak.

Phase 5 stores one seller profile and one store for a Keycloak subject. The seller starts at `PENDING_APPROVAL`. An administrator approves, rejects, suspends, or reactivates that seller and each decision is written to `seller_audit`. The actor cannot change their own seller status. `OPERATOR` cannot. Catalog and inventory call `GET /api/v1/sellers/me` before the seller writes a product, stock level, or campaign. They do not read `identity_db`. A missing or non-`ACTIVE` seller is refused. If identity does not answer, the write is refused. Checkout and public catalog reads do not call this check. Seller Center reads and edits the signed-in seller's store through these APIs. It cannot name another seller.

## Catalog Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8082 as a local process |
| Data | PostgreSQL `catalog_db`. JSONB is the planned store for category-specific attributes. See [ADR-006](../adr/ADR-006-catalog-postgresql.md). |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/catalog/info`. Both stay public.

Phase 2 stores categories, products, image metadata, and flexible attributes in JSONB. A public product read returns `ACTIVE` products only. A seller creates a product as `DRAFT` and publishes it with a status change. The seller id comes from the token. Stock quantities stay in inventory. There is no product variant in this milestone.

A status change into or out of `ACTIVE`, and an update of an `ACTIVE` product, inserts `bazaar.catalog.events` into the catalog outbox in the same transaction. Creating or editing a draft does not. Readiness does not include Kafka.

Public product detail, the default first page of active products, and the default category page can be served from Redis for 60 seconds. Search text, price filters, later pages, and a seller's own list are not cached. A product event drops the cached product and the cached lists. `Bazaar-Read-Source: database` skips the cache; checkout sends that header so the price comes from PostgreSQL. If Redis is down, the catalog reads the database and still answers. Redis is not part of readiness.

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

Checkout returns as soon as the order is stored in `PENDING_INVENTORY`. The order service then sends `inventory.reservation-requested` and, after stock is held, `payment.requested`. Payment success asks inventory to commit. Payment failure, a failed reservation, or a customer cancel releases the hold. If commit keeps failing after a successful payment, the saga asks payment to refund and does not leave that payment as succeeded. A scheduler resends the current command when a step is stuck, and gives up when the step is still stuck after the timeout. Readiness does not include the broker.

## Payment Service

| | |
| --- | --- |
| Technology | Spring Boot |
| Port | 8080 in a container, 8085 as a local process |
| Data | PostgreSQL `payment_db`. |

Phase 1 exposes `GET /actuator/health` and `GET /api/v1/payments/info`. Both stay public.

Phase 2 stores mock payments and payment attempts. A payment is bound to an order id and an amount. The same idempotency key cannot create a second payment, and an order cannot have two pending or successful payments. Success and failure are simulated. There is no card network and no call to the order service yet.

A real status change to `SUCCEEDED`, `FAILED`, or `CANCELLED` inserts one `bazaar.payment.events` row in that same transaction. Creating a pending payment does not. Readiness does not include Kafka. Those facts include `customerId` so another service can route them without reading `payment_db`.

## Notification Service

| | |
| --- | --- |
| Technology | NestJS, Socket.IO |
| Port | 8080 in a container, 8086 as a published host port |
| Data | PostgreSQL `notification_db`. Redis only fans socket events out to more than one instance. |

The service consumes `bazaar.order.events` and `bazaar.payment.events`. It stores `ORDER_CREATED`, `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`, `ORDER_CONFIRMED`, and `ORDER_CANCELLED` for the `customerId` in the event. It does not read the order or payment databases. `GET /api/v1/notifications` returns the rows for the verified token subject. The socket joins `customer:{subject}` from that same token. The browser cannot pick a customer id. A disconnect does not delete the rows. Readiness is the database and does not include Kafka or Redis.

## Storefront

| | |
| --- | --- |
| Technology | Next.js, React, TypeScript |
| Port | 3000 |
| Data | None |

Phase 1 serves a responsive landing page and a gateway connectivity check.

Phase 2 adds the customer shop: product list and detail, a browser cart, Keycloak sign-in, checkout, payment simulation, and order history. The browser calls the storefront. The storefront calls the gateway with the access token from an HTTP-only cookie. Product prices in the cart are only a preview.

The order page also opens a socket to the notification service with that cookie. A notice for the open order is shown as text and the page reads the order again. The badge stays the order service status. Reconnect loads the saved notices and the order. The socket is not a financial record.

## Ownership rules

- Each future database belongs to exactly one service. Tables are not shared.
- The gateway and the storefront do not own a database.
- The gateway does not call a database. It forwards the caller's token.
- Checkout is a saga. The order service does not share tables with catalog, inventory, or payment. The notification service owns `notification_db` and does not read the other databases.

## Later services

These services are not part of the current milestone and have no folders yet.

| Service | Planned stack | Planned data |
| --- | --- | --- |
| Recommendation | Python and FastAPI | Read models derived from events |
| Analytics | C# and .NET | SQL Server read models |

Kafka remains the event bus between services when those flows exist. WebSocket is for browser delivery, not a substitute for the bus.
