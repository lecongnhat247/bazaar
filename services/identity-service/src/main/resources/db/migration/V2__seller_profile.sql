-- A seller is a business profile for one Keycloak subject. Identity does not
-- store a password. The seeded subject matches the imported local seller and
-- the catalog rows for the lamp and the shirt.

CREATE TABLE seller_profile (
    id UUID PRIMARY KEY,
    keycloak_subject VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'REJECTED'))
);

CREATE TABLE store_profile (
    seller_id UUID PRIMARY KEY REFERENCES seller_profile (id),
    store_name VARCHAR(120) NOT NULL,
    description VARCHAR(4000),
    logo_url VARCHAR(500),
    contact_email VARCHAR(320),
    phone VARCHAR(40),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE seller_audit (
    id UUID PRIMARY KEY,
    actor_subject VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    target_subject VARCHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX seller_audit_target_idx ON seller_audit (target_subject, created_at DESC);

INSERT INTO seller_profile (id, keycloak_subject, status, created_at, updated_at, decided_at)
VALUES (
    '22222222-2222-4222-8222-222222222222',
    '11111111-1111-4111-8111-111111111111',
    'ACTIVE',
    TIMESTAMPTZ '2026-11-12 00:00:00+00',
    TIMESTAMPTZ '2026-11-12 00:00:00+00',
    TIMESTAMPTZ '2026-11-12 00:00:00+00'
);

INSERT INTO store_profile (seller_id, store_name, description, updated_at)
VALUES (
    '22222222-2222-4222-8222-222222222222',
    'Local Seller',
    'Seed store for the imported local seller.',
    TIMESTAMPTZ '2026-11-12 00:00:00+00'
);
