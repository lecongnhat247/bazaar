import json

from recommendation.consumer import dead_letter, handle
from recommendation.store import MemoryStore

from factories import LAMP, catalog


class Boom:
    def apply(self, event):
        raise RuntimeError("database down")


def test_a_bad_payload_is_skipped_and_a_duplicate_is_reported():
    store = MemoryStore()
    event = catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp")
    raw = json.dumps(event)
    assert handle(raw, store) == "applied"
    assert handle(raw, store) == "duplicate"
    assert handle("{", store) == "invalid"
    assert handle("[]", store) == "invalid"


def test_a_store_failure_is_left_for_the_caller_to_retry():
    try:
        handle(json.dumps(catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp")), Boom())
        raised = False
    except RuntimeError:
        raised = True
    assert raised


def test_dead_letter_keeps_the_original_value():
    raw = json.dumps({"eventId": "22222222-2222-4222-8222-222222222222", "correlationId": "33333333-3333-4333-8333-333333333333"})
    letter = dead_letter("bazaar.catalog.events", raw, "database down")
    assert letter["originalTopic"] == "bazaar.catalog.events"
    assert letter["eventId"] == "22222222-2222-4222-8222-222222222222"
    assert letter["value"] == raw
