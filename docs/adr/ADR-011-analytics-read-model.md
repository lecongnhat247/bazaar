# ADR-011: Analytics is a SQL Server read model in a .NET service

- Status: Accepted
- Date: 2026-11-18

## Context

Phase 5 needs platform, seller, product, and flash-sale reports. The order service owns the order and the ledger. The payment service owns the payment. The catalog service owns the product. Analytics must not read those databases and must not become a second price or settlement authority.

`order.confirmed` version 1 originally carried the customer and the total. Seller revenue, commission, and product units cannot be invented from that. Commission exists in the order ledger after M4. Settlement is a mock HTTP action and is not published on Kafka. Identity does not publish seller approval. Inventory does not publish a flash-sale-completed fact.

## Decision

- `services/analytics-service` is an ASP.NET Core process on .NET 10. It owns SQL Server database `analytics_db` and nothing else. An empty `ANALYTICS_SQL` uses a memory fold for tests and for a process started without SQL Server.
- It consumes `bazaar.order.events`, `bazaar.payment.events`, and `bazaar.catalog.events` in group `analytics-service`. It does not publish a new topic. A store failure is retried, then copied to the existing `bazaar.dlq`. A bad payload is skipped. A duplicate `eventId` is ignored. An older `occurredAt` does not rewind a newer order, payment, or product.
- `order.confirmed` version 1 gains optional `campaignId`, optional `items` (`productId`, `sellerId`, `productName`, `unitPrice`, `quantity`, `lineTotal`, `discountAmount`), and optional `sellers` (`sellerId`, `gross`, `discount`, `commission`, `rateBps`). The order service writes those from its own rows after the ledger sale is recorded, in the same transaction. Adding optional fields does not bump `eventVersion`.
- Commission is copied from `sellers`. It is not recomputed. A confirmed order with no `sellers` array has unavailable commission, not zero. A confirmed order with no `items` still counts in platform revenue and is reported as `confirmedWithoutLines`. It is not treated as zero seller revenue.
- Platform revenue is the sum of current `CONFIRMED` order totals, grouped by currency. `payment.refunded` updates the payment fact and does not change order revenue.
- Flash-sale figures are confirmed orders that carried `campaignId`. There is no flash-sale-completed event. Category comes from catalog product events, otherwise `unknown`.
- Settlement reports stay `{ available: false, reason: "Settlement is not published on the event stream." }`.
- Day, week, and month are derived in UTC. Week starts on Monday. There is no `DimDate` table. The projection tables are `analytics_event`, `fact_order`, `fact_order_item`, `fact_payment`, `fact_seller_amount`, and `dim_product`. They are rewritten from the fold. That is a practical model, not a warehouse.
- Staff routes require ADMIN or OPERATOR. A seller path id must equal the token subject unless the caller is staff. `?sellerId=` is rejected. Rebuild is ADMIN only and folds stored rows. It cannot replay Kafka.
- SQL Server runs only under Compose profile `analytics`. The default Compose stack and the kind chart do not start it. Readiness is the database, or the memory store. Kafka and Keycloak are not readiness dependencies.

## Consequences

- Historical confirmed events that have no `items` or `sellers` still count as platform revenue and do not invent seller or product figures.
- A platform-funded coupon can show a line discount while the seller snapshot discount is 0, because the ledger records that discount as `PLATFORM_DISCOUNT` rather than a seller `DISCOUNT`. Analytics displays the published snapshot.
- Reports lag the source of truth. `asOf` is the last successful apply. Delivery is at-least-once, not exactly-once, and not real-time.
- The fold is a full scan on one local instance. SQL Server is heavy for a 16 GB laptop and must not run beside kind.
- Admin Portal and Seller Center show these reports. The kind chart does not include them or this service.

## Alternatives considered

Reading `order_db` would make the numbers match the ledger immediately and would also make analytics a second owner of that data. Recomputing commission in this service would drift from the ledger. Treating a missing seller snapshot as zero would hide orders that were confirmed before the line facts existed.
