# ADR-009: Marketplace workflows live in the order service, money is an append-only ledger

- Status: Accepted
- Date: 2026-11-13

## Context

An order can hold lines from several sellers. Phase 5 M4 needs per-seller fulfillment, coupons, a seller commission, and a settlement model that can be audited. There is no real payment provider, shipping provider, or payout provider, and none is added.

The order service already owns `customer_order` and `order_item`. Each line stores the seller subject at purchase time. Checkout is idempotent and the order is created in one database transaction.

## Decision

Ownership

- The order service owns coupons, fulfillment, the ledger, commission rates, and settlements in `order_db`. No new service, no new Kafka topic, and no change to an event contract. A new service would need the same order lines and the same transaction as checkout.
- Seller id always comes from the token, never from a request field. Every seller query is filtered by that id.

Fulfillment

- One fulfillment group per (order, seller), created in the same transaction that moves the order to `CONFIRMED`. Groups move independently: `PENDING -> PACKED -> SHIPPED -> DELIVERED`, with `CANCELLED` allowed before shipping. A shipment reference is required to ship. Every change is recorded in `fulfillment_event`.
- The order level status is derived from the groups (`PENDING`, `PARTIALLY_SHIPPED`, `SHIPPED`, `DELIVERED`, `CANCELLED`) and is never stored.
- A customer can cancel only before confirmation, as before. After confirmation a seller cancels its own group before it ships. That writes reversal entries and does not restock or refund the payment.
- Orders confirmed before this milestone get their group on the first seller action (the sale entries are idempotent).

Coupons

- The browser sends only a coupon code. The server loads the coupon row `FOR UPDATE` inside the checkout transaction, counts live redemptions, and computes the discount. A discount sent by a client is ignored.
- Rules: active status, start and end time, currency, minimum subtotal, optional seller scope, percent or fixed value, maximum discount, total usage limit, per-customer limit. One coupon per order. A coupon cannot be combined with a flash-sale price. An order is never free (at least one cent is charged).
- The discount is spread over eligible lines in whole cents with the largest-remainder method, so line discounts always add up to the order discount. It is stored on each `order_item` and on the order.
- A redemption is `APPLIED` at checkout and `RELEASED` when the order ends as `CANCELLED`, `FAILED`, or `PAYMENT_FAILED`. The unique `order_id` plus the request hash make a checkout replay harmless.
- Coupons are created by ADMIN only. Each coupon states who funds it: `PLATFORM` or `SELLER`.

Commission and ledger

- `ledger_entry` is append-only. A database trigger rejects UPDATE, DELETE, and TRUNCATE. A correction is a `REVERSAL` entry that negates one earlier entry (a unique index allows one reversal per entry).
- Per (order, seller) group the entries always net to what the customer paid for that seller's lines, `gross - discount`:
  - `SELLER_PAYABLE` `SALE` +gross.
  - `SELLER_PAYABLE` `COMMISSION` -commission and `PLATFORM_REVENUE` `COMMISSION` +commission.
  - Seller-funded coupon: `SELLER_PAYABLE` `DISCOUNT` -discount, and commission is taken on the net.
  - Platform-funded coupon: `PLATFORM_REVENUE` `PLATFORM_DISCOUNT` -discount, and commission is taken on the gross.
- Commission is in basis points, `amount * bps / 10000`, rounded HALF_UP to cents. The rate is the latest `commission_rate` row for the seller, else `bazaar.order.commission-default-bps` (1000). The rate used is stored on each entry. Changing a rate affects only later orders.
- Every entry has a deterministic idempotency key, so recording a sale twice inserts nothing.
- Refund adjustment: an ADMIN reverses a shipped or delivered group. It is a ledger adjustment only. Payment-service has no partial refund, so the customer payment is not refunded, and stock is not restocked.

Settlement (mock)

- A run bundles unsettled `SELLER_PAYABLE` entries of `DELIVERED` groups per (seller, currency). Only a positive total creates a `settlement`. A zero or negative balance, for example after a refund that follows a settlement, is carried to a later run.
- Idempotent three ways: a run key (`Idempotency-Key`) is stored once in `settlement_run` and a replay returns the original result; runs are serialised by a Postgres advisory lock; each ledger entry can be linked to one settlement (`UNIQUE ledger_entry_id`).
- Nothing calls a payout provider. The status is `MOCK_PAID`.

Reconciliation

- `GET /api/v1/orders/finance/reconciliation` scans every ledger group and checks it against the order lines (zero once reversed), checks each settlement against its linked entries, and counts fulfillment groups without ledger entries. It is a full scan and reads only.

Roles

- SELLER: own fulfillment and own earnings. ADMIN: creates coupons, sets commission, runs settlement, writes refund adjustments. OPERATOR: reads coupons, ledger, rates, settlements, reconciliation. CUSTOMER: nothing of this.

## Consequences

- Checkout takes a row lock on the coupon it uses. Two checkouts with the same coupon serialise, and a usage limit holds under concurrency.
- Financial history is never rewritten. Reading the ledger shows why a balance is what it is.
- Money flows are still a mock. There is no payout, no tax, no multi-currency conversion, and no partial payment refund.
- A seller cancellation reverses earnings but does not return stock or money to the customer. That needs a payment-service refund command and an inventory release, which are later work.
- Reconciliation is a full scan and is not meant for production-sized ledgers.

## Alternatives considered

A separate finance service would own the ledger but would need order lines and its own copy of coupon state for atomic checkout, and Kafka events for every entry. Storing balances as mutable columns is simpler to read but silently overwrites history and cannot be audited. A promotion engine that trusts a client discount is not acceptable.
