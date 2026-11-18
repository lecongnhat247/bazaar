from datetime import datetime, timedelta, timezone

import jwt
from fastapi.testclient import TestClient

from recommendation.main import create_app
from recommendation.settings import Settings
from recommendation.store import MemoryStore

from factories import LAMP, MUG, SHIRT, catalog, confirmed, view

ISSUER = "http://localhost:8088/realms/bazaar"
SECRET = "test-secret-which-is-long-enough"
ORDER_A = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9002"
ORDER_B = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9003"


def client_with(events: list[dict]) -> TestClient:
    store = MemoryStore()
    for event in events:
        store.apply(event)
    settings = Settings("", "", "bazaar.catalog.events", "bazaar.order.events", "bazaar.dlq", "recommendation-service", ISSUER, "", SECRET)
    return TestClient(create_app(store, settings))


def token(subject: str, role: str) -> dict[str, str]:
    encoded = jwt.encode(
        {
            "sub": subject,
            "iss": ISSUER,
            "exp": datetime.now(timezone.utc) + timedelta(minutes=5),
            "realm_access": {"roles": [role]},
        },
        SECRET,
        algorithm="HS256",
    )
    return {"Authorization": f"Bearer {encoded}"}


def shelf() -> list[dict]:
    return [
        catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp"),
        catalog("44444444-4444-4444-8444-444444444444", SHIRT, "Shirt"),
        catalog("55555555-5555-4555-8555-555555555555", MUG, "Mug"),
        confirmed("11111111-1111-4111-8111-111111111111", ORDER_A, "ada", [(LAMP, 1), (SHIRT, 1)]),
        confirmed("77777777-7777-4777-8777-777777777777", ORDER_B, "bea", [(MUG, 1)]),
        view("66666666-6666-4666-8666-666666666666", LAMP, "ada"),
        view("99999999-9999-4999-8999-999999999999", MUG, "bea"),
    ]


def test_home_is_public_and_hides_customer_sections_until_a_token_is_sent():
    client = client_with([])
    empty = client.get("/api/v1/recommendations/home")
    assert empty.status_code == 200
    assert empty.json() == {"popular": [], "trending": []}
    body = client_with(shelf()).get("/api/v1/recommendations/home").json()
    assert "forYou" not in body
    assert "recentlyViewed" not in body
    assert body["popular"]


def test_customer_sections_follow_the_token_and_ignore_a_customer_query():
    client = client_with(shelf())
    ada = client.get("/api/v1/recommendations/home", headers=token("ada", "CUSTOMER"))
    bea = client.get("/api/v1/recommendations/recently-viewed", headers=token("bea", "CUSTOMER"))
    assert ada.status_code == 200
    assert ada.json()["forYou"][0]["productId"] == MUG
    assert ada.json()["forYou"][0]["reason"] == "category"
    assert ada.json()["recentlyViewed"][0]["productId"] == LAMP
    assert [item["productId"] for item in bea.json()["items"]] == [MUG]
    bea_home = client.get("/api/v1/recommendations/home", headers=token("bea", "CUSTOMER")).json()
    assert bea_home["forYou"][0]["productId"] != ada.json()["forYou"][0]["productId"]
    rejected = client.get("/api/v1/recommendations/home?customerId=ada")
    assert rejected.status_code == 400


def test_recently_viewed_and_rebuild_require_the_right_caller():
    client = client_with(shelf())
    assert client.get("/api/v1/recommendations/recently-viewed").status_code == 401
    assert client.post("/api/v1/recommendations/rebuild", headers=token("ada", "CUSTOMER")).status_code == 403
    bad = jwt.encode({"sub": "ada", "iss": "https://other.example", "exp": datetime.now(timezone.utc) + timedelta(minutes=5)}, SECRET, algorithm="HS256")
    assert client.get("/api/v1/recommendations/home", headers={"Authorization": f"Bearer {bad}"}).status_code == 401
    rebuilt = client.post("/api/v1/recommendations/rebuild", headers=token("admin", "ADMIN"))
    assert rebuilt.status_code == 200
    assert rebuilt.json()["events"] == len(shelf())
    again = client.get("/api/v1/recommendations/products/" + LAMP + "/related")
    assert again.status_code == 200
    assert again.json()["items"][0]["productId"] == SHIRT


def test_invalid_ids_and_limits_are_rejected_and_an_unknown_product_is_empty():
    client = client_with([])
    assert client.get("/api/v1/recommendations/products/nope/related").status_code == 400
    assert client.get("/api/v1/recommendations/trending?limit=0").status_code == 400
    assert client.get("/api/v1/recommendations/categories/" + LAMP).json() == {"categoryId": LAMP, "items": []}
    missing = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d10ff"
    assert client.get(f"/api/v1/recommendations/products/{missing}/related").json()["items"] == []


def test_ready_does_not_ask_kafka_and_a_new_event_is_on_the_next_read():
    store = MemoryStore()
    settings = Settings("", "", "bazaar.catalog.events", "bazaar.order.events", "bazaar.dlq", "recommendation-service", ISSUER, "", SECRET)
    client = TestClient(create_app(store, settings))
    assert client.get("/health/live").json()["status"] == "UP"
    assert client.get("/health/ready").json()["status"] == "UP"
    assert client.get("/api/v1/recommendations/home").json()["popular"] == []
    store.apply(catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp"))
    assert client.get("/api/v1/recommendations/home").json()["popular"][0]["productId"] == LAMP
