"""Fold marketplace events into a recommendation read model.

The fold is the source of truth. A duplicate eventId is ignored. A catalog
fact changes the product snapshot only when it is at least as new as the
snapshot already stored, so an older event can arrive later without rewinding
the product. Purchases and views are additive. Delivery is at-least-once.
This is not exactly-once.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    re.IGNORECASE,
)
CATALOG_TYPES = frozenset({
    "catalog.product-published",
    "catalog.product-updated",
    "catalog.product-unpublished",
})
MAX_QUANTITY = 10_000


@dataclass
class Product:
    product_id: str
    name: str
    category_id: str | None
    seller_id: str | None
    status: str
    price: str | None
    currency: str | None
    catalog_at: datetime


@dataclass
class Line:
    order_id: str
    event_id: str
    occurred_at: datetime
    customer_id: str | None
    product_id: str
    quantity: int


@dataclass
class View:
    event_id: str
    occurred_at: datetime
    customer_id: str
    product_id: str


@dataclass
class State:
    products: dict[str, Product] = field(default_factory=dict)
    lines: list[Line] = field(default_factory=list)
    views: list[View] = field(default_factory=list)
    seen: set[str] = field(default_factory=set)


def parse_time(value: object) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    text = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def project(events: list[dict]) -> State:
    """Replay facts in time order. The result matches one-by-one ingest."""
    state = State()
    ordered = sorted(events, key=lambda event: (parse_time(event.get("occurredAt")) or _epoch(), str(event.get("eventId"))))
    for event in ordered:
        ingest(state, event)
    return state


def ingest(state: State, event: dict) -> str:
    """Apply one envelope. Returns applied, duplicate, ignored, or invalid."""
    if not isinstance(event, dict):
        return "invalid"
    event_id = event.get("eventId")
    if not isinstance(event_id, str) or not UUID_RE.match(event_id):
        return "invalid"
    if event_id in state.seen:
        return "duplicate"
    version = event.get("eventVersion")
    if isinstance(version, bool) or not isinstance(version, int) or version < 1:
        return "invalid"
    event_type = event.get("eventType")
    occurred = parse_time(event.get("occurredAt"))
    if not isinstance(event_type, str) or occurred is None:
        return "invalid"
    payload = event.get("payload") if event.get("payload") is not None else {}
    if not isinstance(payload, dict):
        return "invalid"

    if event_type in CATALOG_TYPES:
        if not _catalog(state, event, payload, occurred):
            return "invalid"
    elif event_type == "catalog.product-viewed":
        if not _view(state, event, payload, occurred):
            return "invalid"
    elif event_type == "order.confirmed":
        if not _purchase(state, event, payload, occurred):
            return "ignored"
    else:
        return "ignored"
    state.seen.add(event_id)
    return "applied"


def _catalog(state: State, event: dict, payload: dict, occurred: datetime) -> bool:
    product_id = event.get("aggregateId")
    name = payload.get("name")
    status = payload.get("status")
    if not isinstance(product_id, str) or not UUID_RE.match(product_id):
        return False
    if not isinstance(name, str) or not name.strip() or len(name) > 160:
        return False
    if not isinstance(status, str) or not status.strip() or len(status) > 32:
        return False
    current = state.products.get(product_id)
    if current is not None and occurred < current.catalog_at:
        return True
    state.products[product_id] = Product(
        product_id=product_id,
        name=name.strip(),
        category_id=_uuid(payload.get("categoryId")),
        seller_id=_text(payload.get("sellerId"), 64),
        status=status.strip(),
        price=_price(payload.get("price")),
        currency=_currency(payload.get("currency")),
        catalog_at=occurred,
    )
    return True


def _view(state: State, event: dict, payload: dict, occurred: datetime) -> bool:
    product_id = event.get("aggregateId")
    customer_id = payload.get("customerId")
    if not isinstance(product_id, str) or not UUID_RE.match(product_id):
        return False
    if not isinstance(customer_id, str) or not customer_id.strip() or len(customer_id) > 64:
        return False
    state.views.append(View(
        event_id=str(event["eventId"]),
        occurred_at=occurred,
        customer_id=customer_id.strip(),
        product_id=product_id,
    ))
    return True


def _purchase(state: State, event: dict, payload: dict, occurred: datetime) -> bool:
    items = payload.get("items")
    if not isinstance(items, list) or not items:
        return False
    order_id = event.get("aggregateId")
    if not isinstance(order_id, str) or not UUID_RE.match(order_id):
        return False
    customer = payload.get("customerId")
    customer_id = customer.strip() if isinstance(customer, str) and customer.strip() and len(customer) <= 64 else None
    kept: list[Line] = []
    seen_products: set[str] = set()
    for item in items:
        if not isinstance(item, dict):
            continue
        product_id = item.get("productId")
        quantity = item.get("quantity")
        if not isinstance(product_id, str) or not UUID_RE.match(product_id) or product_id in seen_products:
            continue
        if isinstance(quantity, bool) or not isinstance(quantity, int) or quantity < 1 or quantity > MAX_QUANTITY:
            continue
        seen_products.add(product_id)
        kept.append(Line(
            order_id=order_id,
            event_id=str(event["eventId"]),
            occurred_at=occurred,
            customer_id=customer_id,
            product_id=product_id,
            quantity=quantity,
        ))
    if not kept:
        return False
    state.lines.extend(kept)
    return True


def _uuid(value: object) -> str | None:
    if isinstance(value, str) and UUID_RE.match(value):
        return value
    return None


def _text(value: object, limit: int) -> str | None:
    if isinstance(value, str) and value.strip() and len(value) <= limit:
        return value.strip()
    return None


def _currency(value: object) -> str | None:
    if isinstance(value, str) and len(value.strip()) == 3:
        return value.strip().upper()
    return None


def _price(value: object) -> str | None:
    if isinstance(value, str) and re.fullmatch(r"\d+\.\d{2}", value):
        return value
    return None


def _epoch() -> datetime:
    return datetime.min.replace(tzinfo=timezone.utc)
