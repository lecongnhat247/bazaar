# ADR-008: Seller profile lives in the identity service

- Status: Accepted
- Date: 2026-11-12

## Context

Phase 5 needs a seller that is more than a Keycloak role. A seller has a store, an approval state, and an audit trail. Catalog products and inventory rows already store the Keycloak subject as `seller_id`. The identity service already stores that subject, the application roles, and an account status. A new seller service would own a second copy of the same subject.

Catalog and inventory must not read `identity_db`. Checkout, reservation, and payment must keep working when a seller is suspended: stock already sold is not reversed by a profile change.

## Decision

- The identity service owns `seller_profile`, `store_profile`, and `seller_audit` in `identity_db`.
- One seller has one store. The only identity key is the Keycloak subject. Passwords and Keycloak credentials are not copied.
- A new seller starts at `PENDING_APPROVAL`. An `ADMIN` may approve, reject, suspend, or reactivate. The actor cannot change their own seller status. `OPERATOR` has none of these actions.
- The imported local seller subject `11111111-1111-4111-8111-111111111111` is seeded `ACTIVE`, because the catalog seed already assigns the lamp and the shirt to that subject.
- Catalog and inventory call `GET /api/v1/sellers/me` with the caller's bearer token before a seller creates or publishes a product, sets on-hand stock, or creates a campaign. `ACTIVE` is required. Any other status, including a missing profile, is HTTP 403. If identity does not answer, the write is HTTP 503.
- An `ADMIN` skips that check. Public catalog reads, checkout, and the inventory saga do not call identity.
- Seller Center and the admin portal are later applications. They are not part of this decision.

## Consequences

- A seller who is not `ACTIVE` cannot open new catalog or stock writes. Existing products stay as they are until an allowed status change.
- Catalog and inventory now depend on identity for those writes. Their readiness probes still do not include identity.
- A Keycloak subject that does not match the seeded id has no `ACTIVE` store until that seller onboards and an administrator approves them.

## Alternatives considered

A separate seller service keeps identity limited to login profiles. It also adds a service, a database, and a second subject map before the domain has more than one store per seller. Leaving the seller as only a role cannot record approval or suspension.
