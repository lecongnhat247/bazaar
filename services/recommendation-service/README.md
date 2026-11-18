# Recommendation service

Python 3.14 and FastAPI. This service recommends products from Kafka facts. It does not own a price, a stock count, or an order.

Readiness is `GET /health/ready` and checks only `recommendation_db`. It does not check Kafka, Redis, or Keycloak. Redis is not used. There is no cache in front of the fold, so a stored event is visible on the next read.

## APIs

| Method | Path | Who |
| --- | --- | --- |
| GET | `/api/v1/recommendations/home` | Public. A valid token adds `forYou` and `recentlyViewed` for that subject only. |
| GET | `/api/v1/recommendations/trending` | Public. |
| GET | `/api/v1/recommendations/products/{productId}/related` | Public. |
| GET | `/api/v1/recommendations/categories/{categoryId}` | Public. |
| GET | `/api/v1/recommendations/recently-viewed` | The token subject. |
| POST | `/api/v1/recommendations/rebuild` | ADMIN. Folds the stored events again. |

`customerId` is rejected. `limit` is 1 to 20 and defaults to 8. An empty catalog returns empty lists, not an error.

A customer records a view with `POST /api/v1/products/{id}/views` on the catalog service. That writes `catalog.product-viewed`. The recommendation service only consumes it.

## Ranking

Popularity is purchased units. Before any purchase, the popular list is the newest active products. Trending uses a 7 day half-life: a purchased unit weighs 5 and a view weighs 1. Related products are co-purchases, then the same category. `forYou` is the same co-purchase rule for the signed-in customer, and falls back to categories they viewed. It is empty until then. This is not a learned model.

A product appears only when the latest catalog snapshot is `ACTIVE`. `price` on a card is a copy of the catalog event. Checkout still asks the catalog.

The fold scans every stored event. That is small enough for this local marketplace and is not a production index.

## Events

Consumed from `bazaar.catalog.events` and `bazaar.order.events`, group `recommendation-service`:

- `catalog.product-published`, `catalog.product-updated`, `catalog.product-unpublished`
- `catalog.product-viewed`
- `order.confirmed` when `payload.items` is present

`order.confirmed` version 1 without `items` is ignored. No cart event exists, because the cart stays in the browser. A duplicate `eventId` is stored once. An older catalog event does not overwrite a newer snapshot. A bad payload is skipped. A store failure is retried, then copied to `bazaar.dlq`. Delivery is at-least-once, not exactly-once. One consumer instance is the intended local shape.

## Local test

```bash
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt pytest httpx
.venv/Scripts/python -m pytest
```

On Ubuntu the Scripts path is `.venv/bin`. Leave `DATABASE_URL` unset. The tests use the in-memory log.
