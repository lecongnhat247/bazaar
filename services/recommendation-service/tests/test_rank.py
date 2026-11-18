from recommendation.model import project
from recommendation.rank import for_you, popular, recently_viewed, related, trending

from factories import HOME, LAMP, MUG, NOW, SHIRT, at, catalog, confirmed, view

ORDER_A = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9002"
ORDER_B = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9003"
OTHER_CATEGORY = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0003"


def shelf():
    return project([
        catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp", when=at(-3)),
        catalog("44444444-4444-4444-8444-444444444444", SHIRT, "Shirt", when=at(-2)),
        catalog("55555555-5555-4555-8555-555555555555", MUG, "Mug", when=at(-1), category=OTHER_CATEGORY),
    ])


def test_empty_catalog_returns_empty_lists():
    assert popular(project([]), 8) == []
    assert trending(project([]), NOW, 8) == []
    assert related(project([]), LAMP, 8) == []
    assert for_you(project([]), "ada", 8) == []
    assert recently_viewed(project([]), "ada", 8) == []


def test_popular_falls_back_to_newest_active_products_before_anyone_buys():
    items = popular(shelf(), 2)
    assert [item["productId"] for item in items] == [MUG, SHIRT]
    assert items[0]["reason"] == "recently-published"
    assert items[0]["price"] == "25.00"


def test_popularity_uses_units_and_hides_inactive_products():
    state = shelf()
    state = project([
        *[_event for _event in _published()],
        confirmed("11111111-1111-4111-8111-111111111111", ORDER_A, "ada", [(LAMP, 1)], at(-10)),
        confirmed("77777777-7777-4777-8777-777777777777", ORDER_B, "bea", [(SHIRT, 3)], at()),
        catalog("88888888-8888-4888-8888-888888888888", MUG, "Mug", status="INACTIVE", when=at()),
    ])
    items = popular(state, 8)
    assert [item["productId"] for item in items] == [SHIRT, LAMP]
    assert items[0]["reason"] == "popular"


def test_trending_weights_recent_purchases_over_old_ones_and_views():
    state = project([
        *_published(),
        confirmed("11111111-1111-4111-8111-111111111111", ORDER_A, "ada", [(LAMP, 1)], at(-7)),
        confirmed("77777777-7777-4777-8777-777777777777", ORDER_B, "bea", [(SHIRT, 1)], at()),
        view("66666666-6666-4666-8666-666666666666", MUG, "ada", at()),
    ])
    items = trending(state, NOW, 8)
    assert [item["productId"] for item in items] == [SHIRT, LAMP, MUG]
    assert items[0]["reason"] == "trending"


def test_related_prefers_co_purchase_then_the_same_category():
    state = project([
        *_published(),
        confirmed("11111111-1111-4111-8111-111111111111", ORDER_A, "ada", [(LAMP, 1), (SHIRT, 1)]),
    ])
    items = related(state, LAMP, 8)
    assert items[0]["productId"] == SHIRT
    assert items[0]["reason"] == "copurchase"
    assert MUG not in [item["productId"] for item in items]


def test_for_you_uses_only_that_customers_purchases():
    state = project([
        *_published(),
        confirmed("11111111-1111-4111-8111-111111111111", ORDER_A, "ada", [(LAMP, 1)]),
        confirmed("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9004", "cy", [(LAMP, 1), (SHIRT, 1)]),
        confirmed("77777777-7777-4777-8777-777777777777", ORDER_B, "bea", [(MUG, 1)]),
        view("66666666-6666-4666-8666-666666666666", LAMP, "ada"),
        view("99999999-9999-4999-8999-999999999999", MUG, "bea"),
    ])
    ada = for_you(state, "ada", 8)
    bea = for_you(state, "bea", 8)
    assert ada[0] == {"productId": SHIRT, "name": "Shirt", "reason": "copurchase", "categoryId": HOME, "price": "25.00", "currency": "USD"}
    assert all(item["reason"] != "copurchase" for item in bea)
    assert [item["productId"] for item in recently_viewed(state, "ada", 8)] == [LAMP]
    assert [item["productId"] for item in recently_viewed(state, "bea", 8)] == [MUG]
    assert for_you(project(_published()), "ada", 8) == []


def test_for_you_uses_viewed_categories_when_the_customer_has_not_purchased():
    state = project([*_published(), view("66666666-6666-4666-8666-666666666666", LAMP, "ada")])
    items = for_you(state, "ada", 8)
    assert [item["productId"] for item in items] == [SHIRT]
    assert items[0]["reason"] == "category"


def _published():
    return [
        catalog("22222222-2222-4222-8222-222222222222", LAMP, "Lamp", when=at(-3)),
        catalog("44444444-4444-4444-8444-444444444444", SHIRT, "Shirt", when=at(-2)),
        catalog("55555555-5555-4555-8555-555555555555", MUG, "Mug", when=at(-1), category=OTHER_CATEGORY),
    ]
