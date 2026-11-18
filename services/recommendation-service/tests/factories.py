from datetime import datetime, timedelta, timezone

LAMP = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001"
SHIRT = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1002"
MUG = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1004"
HOME = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002"
NOW = datetime(2026, 11, 18, tzinfo=timezone.utc)


def at(days: int = 0) -> str:
    return (NOW + timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%SZ")


def catalog(event_id: str, product_id: str, name: str, status: str = "ACTIVE", when: str | None = None, category: str = HOME) -> dict:
    return {
        "eventId": event_id,
        "eventType": "catalog.product-published" if status == "ACTIVE" else "catalog.product-unpublished",
        "eventVersion": 1,
        "aggregateId": product_id,
        "aggregateType": "product",
        "occurredAt": when or at(),
        "correlationId": event_id,
        "producer": "catalog-service",
        "payload": {
            "name": name,
            "sku": "SKU",
            "price": "25.00",
            "currency": "USD",
            "status": status,
            "categoryId": category,
            "sellerId": "seller-1",
        },
    }


def updated(event_id: str, product_id: str, name: str, when: str) -> dict:
    event = catalog(event_id, product_id, name, when=when)
    event["eventType"] = "catalog.product-updated"
    return event


def view(event_id: str, product_id: str, customer_id: str, when: str | None = None) -> dict:
    return {
        "eventId": event_id,
        "eventType": "catalog.product-viewed",
        "eventVersion": 1,
        "aggregateId": product_id,
        "aggregateType": "product",
        "occurredAt": when or at(),
        "correlationId": event_id,
        "producer": "catalog-service",
        "payload": {"customerId": customer_id, "name": "Lamp", "status": "ACTIVE", "categoryId": HOME},
    }


def confirmed(event_id: str, order_id: str, customer_id: str, items: list[tuple[str, int]], when: str | None = None) -> dict:
    return {
        "eventId": event_id,
        "eventType": "order.confirmed",
        "eventVersion": 1,
        "aggregateId": order_id,
        "aggregateType": "order",
        "occurredAt": when or at(),
        "correlationId": event_id,
        "producer": "order-service",
        "payload": {
            "status": "CONFIRMED",
            "currency": "USD",
            "total": "25.00",
            "customerId": customer_id,
            "items": [{"productId": product_id, "quantity": quantity} for product_id, quantity in items],
        },
    }
