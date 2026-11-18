from recommendation.model import ingest, project
from recommendation.store import MemoryStore

from factories import LAMP, MUG, SHIRT, at, catalog, confirmed, updated, view

ORDER = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9002"
OTHER = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9003"


def test_duplicate_event_does_not_count_twice():
    store = MemoryStore()
    event = confirmed("11111111-1111-4111-8111-111111111111", ORDER, "ada", [(LAMP, 2)])
    assert store.apply(catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp")) == "applied"
    assert store.apply(event) == "applied"
    assert store.apply(event) == "duplicate"
    assert sum(line.quantity for line in store.state().lines) == 2


def test_purchase_before_the_catalog_event_is_kept():
    store = MemoryStore()
    store.apply(confirmed("11111111-1111-4111-8111-111111111111", ORDER, "ada", [(LAMP, 1)], at(-1)))
    store.apply(catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp", when=at()))
    state = store.state()
    assert state.products[LAMP].status == "ACTIVE"
    assert state.lines[0].product_id == LAMP


def test_an_older_catalog_event_does_not_rewind_the_name():
    store = MemoryStore()
    store.apply(updated("33333333-3333-4333-8333-333333333333", LAMP, "New lamp", at()))
    store.apply(catalog("22222222-2222-4222-8222-222222222222", LAMP, "Old lamp", when=at(-2)))
    assert store.state().products[LAMP].name == "New lamp"
    assert store.rebuild() == 2
    assert store.state().products[LAMP].name == "New lamp"


def test_replay_matches_arrival_order():
    events = [
        catalog("22222222-2222-4222-8222-222222222222", LAMP, "Old lamp", when=at(-2)),
        updated("33333333-3333-4333-8333-333333333333", LAMP, "New lamp", at()),
        confirmed("11111111-1111-4111-8111-111111111111", ORDER, "ada", [(LAMP, 1), (SHIRT, 1)]),
        catalog("44444444-4444-4444-8444-444444444444", SHIRT, "Shirt"),
    ]
    forward = project(events)
    backward = project(list(reversed(events)))
    assert forward.products[LAMP].name == backward.products[LAMP].name == "New lamp"
    assert len(forward.lines) == len(backward.lines) == 2


def test_confirmed_without_items_and_unknown_events_are_ignored():
    state_store = MemoryStore()
    historical = confirmed("11111111-1111-4111-8111-111111111111", ORDER, "ada", [])
    historical["payload"].pop("items")
    assert state_store.apply(historical) == "ignored"
    assert state_store.apply({"eventId": "55555555-5555-4555-8555-555555555555", "eventType": "payment.succeeded", "eventVersion": 1, "occurredAt": at(), "aggregateId": ORDER}) == "ignored"
    assert state_store.apply({"eventId": "not-a-uuid"}) == "invalid"
    assert state_store.state().lines == []


def test_a_bad_line_is_dropped_and_a_good_line_remains():
    event = confirmed(
        "11111111-1111-4111-8111-111111111111",
        ORDER,
        "ada",
        [(LAMP, 1), (SHIRT, 0), (MUG, 10_001)],
    )
    event["payload"]["items"][1]["quantity"] = 0
    state = project([event])
    assert [line.product_id for line in state.lines] == [LAMP]


def test_view_without_a_customer_is_invalid():
    event = view("66666666-6666-4666-8666-666666666666", LAMP, "ada")
    event["payload"].pop("customerId")
    assert ingest(project([]), event) == "invalid"


def test_version_below_one_is_invalid():
    event = catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp")
    event["eventVersion"] = 0
    assert ingest(project([]), event) == "invalid"
