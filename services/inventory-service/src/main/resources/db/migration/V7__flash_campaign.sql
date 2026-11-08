-- A flash sale quota is separate from warehouse stock. Both are checked in one
-- transaction. remaining cannot rise above quota, and a customer hold cannot be
-- negative. The partial index is written only for campaign reservations.
ALTER TABLE stock_reservation ADD COLUMN campaign_id UUID;

CREATE TABLE flash_campaign (
    id UUID PRIMARY KEY,
    seller_id VARCHAR(64) NOT NULL,
    product_id UUID NOT NULL REFERENCES inventory_item (product_id),
    price NUMERIC(19, 2) NOT NULL CHECK (price > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    quota INT NOT NULL CHECK (quota > 0),
    remaining INT NOT NULL CHECK (remaining >= 0 AND remaining <= quota),
    per_customer_limit INT NOT NULL CHECK (per_customer_limit > 0),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (starts_at < ends_at),
    CHECK (status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'ENDED', 'CANCELLED'))
);

CREATE TABLE flash_hold (
    campaign_id UUID NOT NULL REFERENCES flash_campaign (id),
    customer_id VARCHAR(64) NOT NULL,
    held INT NOT NULL CHECK (held >= 0),
    committed INT NOT NULL CHECK (committed >= 0),
    PRIMARY KEY (campaign_id, customer_id)
);

CREATE INDEX stock_reservation_campaign_idx ON stock_reservation (campaign_id) WHERE campaign_id IS NOT NULL;
