"""Rule-based ranking.

Assumptions, all local and replaceable:

- Popularity is the sum of purchased units. Recency does not change it.
  When nobody has purchased anything, the popular list falls back to the
  newest active catalog snapshots so a new shop is not a blank page.
- Trending decays with a 7 day half-life. A purchase unit weighs 5. A view
  weighs 1. A future timestamp counts as happening now. A product with a
  score of zero is left off the trending list.
- Related products are other products bought in the same confirmed order,
  counted once per order, then filled with other active products in the same
  category.
- forYou is item-to-item co-purchase for the authenticated customer. It is
  not a learned model. With no purchases, it falls back to other active
  products in categories that customer has viewed. It is empty until then.
- Recently viewed keeps the latest view of each active product for that
  customer only.
- A product is recommended only when the latest catalog snapshot says ACTIVE.
  The copied price is a display hint. Catalog remains the price authority.
- Lists are a full scan of the stored facts. That is not a production-scale
  index. There is no Redis cache, so a new fact is visible on the next read.
"""

from __future__ import annotations

from datetime import datetime, timezone

from recommendation.model import Product, State

HALF_LIFE_SECONDS = 7 * 24 * 60 * 60
PURCHASE_WEIGHT = 5
VIEW_WEIGHT = 1


def home(state: State, now: datetime, limit: int, customer_id: str | None) -> dict:
    body = {
        "popular": popular(state, limit),
        "trending": trending(state, now, limit),
    }
    if customer_id:
        body["forYou"] = for_you(state, customer_id, limit)
        body["recentlyViewed"] = recently_viewed(state, customer_id, limit)
    return body


def popular(state: State, limit: int) -> list[dict]:
    products = _active(state)
    if not products:
        return []
    if all(_units(state, product.product_id) == 0 for product in products):
        products.sort(key=lambda product: (-product.catalog_at.timestamp(), product.name.casefold(), product.product_id))
        return [_card(product, "recently-published") for product in products[:limit]]
    products.sort(key=lambda product: _popular_key(state, product))
    return [_card(product, "popular") for product in products[:limit]]


def trending(state: State, now: datetime, limit: int) -> list[dict]:
    scored = [( _trend(state, product.product_id, now), product) for product in _active(state)]
    scored = [item for item in scored if item[0] > 0]
    scored.sort(key=lambda item: (-item[0], item[1].name.casefold(), item[1].product_id))
    return [_card(product, "trending") for _, product in scored[:limit]]


def related(state: State, product_id: str, limit: int) -> list[dict]:
    anchor = state.products.get(product_id)
    chosen: list[dict] = []
    seen = {product_id}
    for count, other in _neighbors(state, product_id):
        product = state.products.get(other)
        if product is None or product.status != "ACTIVE" or other in seen:
            continue
        chosen.append(_card(product, "copurchase"))
        seen.add(other)
        if len(chosen) >= limit:
            return chosen
    if anchor is not None and anchor.category_id:
        fillers = [
            product for product in _active(state)
            if product.category_id == anchor.category_id and product.product_id not in seen
        ]
        fillers.sort(key=lambda product: _popular_key(state, product))
        for product in fillers:
            chosen.append(_card(product, "category"))
            if len(chosen) >= limit:
                break
    return chosen


def category(state: State, category_id: str, limit: int) -> list[dict]:
    products = [product for product in _active(state) if product.category_id == category_id]
    products.sort(key=lambda product: _popular_key(state, product))
    return [_card(product, "category") for product in products[:limit]]


def recently_viewed(state: State, customer_id: str, limit: int) -> list[dict]:
    latest: dict[str, datetime] = {}
    for view in state.views:
        if view.customer_id != customer_id:
            continue
        previous = latest.get(view.product_id)
        if previous is None or view.occurred_at > previous:
            latest[view.product_id] = view.occurred_at
    ranked: list[tuple[datetime, Product]] = []
    for product_id, seen_at in latest.items():
        product = state.products.get(product_id)
        if product is not None and product.status == "ACTIVE":
            ranked.append((seen_at, product))
    ranked.sort(key=lambda item: (-item[0].timestamp(), item[1].name.casefold(), item[1].product_id))
    return [_card(product, "recent") for _, product in ranked[:limit]]


def for_you(state: State, customer_id: str, limit: int) -> list[dict]:
    purchased = {line.product_id for line in state.lines if line.customer_id == customer_id}
    if purchased:
        scores: dict[str, int] = {}
        for product_id in purchased:
            for count, other in _neighbors(state, product_id):
                if other in purchased:
                    continue
                scores[other] = scores.get(other, 0) + count
        ranked = []
        for product_id, count in scores.items():
            product = state.products.get(product_id)
            if product is not None and product.status == "ACTIVE":
                ranked.append((count, product))
        ranked.sort(key=lambda item: (-item[0], item[1].name.casefold(), item[1].product_id))
        chosen = [_card(product, "copurchase") for _, product in ranked[:limit]]
        if len(chosen) >= limit:
            return chosen
        seen = purchased | {item["productId"] for item in chosen}
        categories = {
            state.products[product_id].category_id
            for product_id in purchased
            if product_id in state.products and state.products[product_id].category_id
        }
        fillers = [
            product for product in _active(state)
            if product.category_id in categories and product.product_id not in seen
        ]
        fillers.sort(key=lambda product: _popular_key(state, product))
        for product in fillers:
            chosen.append(_card(product, "category"))
            if len(chosen) >= limit:
                break
        return chosen

    viewed = {item["productId"] for item in recently_viewed(state, customer_id, 50)}
    if not viewed:
        return []
    categories = {
        state.products[product_id].category_id
        for product_id in viewed
        if product_id in state.products and state.products[product_id].category_id
    }
    fillers = [
        product for product in _active(state)
        if product.category_id in categories and product.product_id not in viewed
    ]
    fillers.sort(key=lambda product: _popular_key(state, product))
    return [_card(product, "category") for product in fillers[:limit]]


def _active(state: State) -> list[Product]:
    return [product for product in state.products.values() if product.status == "ACTIVE"]


def _units(state: State, product_id: str) -> int:
    return sum(line.quantity for line in state.lines if line.product_id == product_id)


def _views(state: State, product_id: str) -> int:
    return sum(1 for view in state.views if view.product_id == product_id)


def _popular_key(state: State, product: Product) -> tuple:
    return (-_units(state, product.product_id), -_views(state, product.product_id), product.name.casefold(), product.product_id)


def _trend(state: State, product_id: str, now: datetime) -> float:
    score = 0.0
    for line in state.lines:
        if line.product_id == product_id:
            score += line.quantity * PURCHASE_WEIGHT * _decay(now, line.occurred_at)
    for view in state.views:
        if view.product_id == product_id:
            score += VIEW_WEIGHT * _decay(now, view.occurred_at)
    return score


def _decay(now: datetime, occurred: datetime) -> float:
    age = (now - occurred).total_seconds()
    if age <= 0:
        return 1.0
    return 0.5 ** (age / HALF_LIFE_SECONDS)


def _neighbors(state: State, product_id: str) -> list[tuple[int, str]]:
    orders: dict[str, set[str]] = {}
    for line in state.lines:
        orders.setdefault(line.order_id, set()).add(line.product_id)
    counts: dict[str, int] = {}
    for products in orders.values():
        if product_id not in products:
            continue
        for other in products:
            if other != product_id:
                counts[other] = counts.get(other, 0) + 1
    ranked = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    return [(count, product_id) for product_id, count in ranked]


def _card(product: Product, reason: str) -> dict:
    card = {"productId": product.product_id, "name": product.name, "reason": reason}
    if product.category_id:
        card["categoryId"] = product.category_id
    if product.price and product.currency:
        card["price"] = product.price
        card["currency"] = product.currency
    return card


def utcnow() -> datetime:
    return datetime.now(timezone.utc)
