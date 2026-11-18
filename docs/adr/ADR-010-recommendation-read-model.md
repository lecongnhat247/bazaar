# ADR-010: Recommendations are a read model in a Python service

- Status: Accepted
- Date: 2026-11-18

## Context

Phase 5 needs popular, trending, related, category, and recently viewed products. Personalized suggestions are a later extension of those rules, not a separate model. The catalog service owns the product and its price. The order service owns the order. A recommendation must not read `catalog_db` or `order_db`, and it must not become the price used at checkout.

The events that already exist are not enough on their own. `order.confirmed` version 1 names the customer and the total, not the products. Catalog events name the product, not the category. Nothing records that a customer opened a product. The cart never leaves the browser, so there is no cart event to consume.

## Decision

- `services/recommendation-service` is a Python 3.14 FastAPI process. It owns `recommendation_db` and nothing else.
- It consumes `bazaar.catalog.events` and `bazaar.order.events` in group `recommendation-service`. It does not publish a new topic. A failed apply is copied to the existing `bazaar.dlq`.
- Catalog events gain optional `categoryId` and `sellerId` on version 1. Adding an optional field does not bump `eventVersion`. `order.confirmed` gains an optional `items` array of `productId` and `quantity` on version 1. Receivers that do not know those fields ignore them.
- A new fact, `catalog.product-viewed` version 1, is written by the catalog service when an authenticated customer opens an active product through `POST /api/v1/products/{id}/views`. The payload carries `customerId`, `name`, `status`, and `categoryId`. It does not carry a price. Anonymous browsing does not emit it.
- The service stores each new `eventId` once and folds the log on read. Ranking is popularity, a 7 day half-life, co-purchase, and category similarity. `forYou` and recently viewed use only the token subject. A `customerId` query is rejected. There is no Redis cache and no learned model.
- Rebuild folds the stored rows again. It cannot replay Kafka, because local retention is one hour.
- Readiness is the database. Kafka and Keycloak are not readiness dependencies. The kind chart does not include this service.

## Consequences

- Checkout, notification, and the catalog cache keep working. Notification ignores the new optional fields. A catalog view evicts that product from the catalog cache because the event type starts with `catalog.product-`.
- Historical confirmed orders that were published before `items` existed do not affect popularity.
- Prices on a recommendation card can be stale. The catalog remains the price authority.
- The fold is a full scan and the consumer is a single local instance. That is not a production-scale recommendation system.
- Seller Center and the admin portal do not show recommendations. The storefront home and product page do.

## Alternatives considered

Putting the ranks in the catalog service would keep one less process, and it would also put purchase counts next to the price authority. A learned model needs more behavior than this marketplace has. Trusting a client-supplied customer id would leak one customer's views to another.
