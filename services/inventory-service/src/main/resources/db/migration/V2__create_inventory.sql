CREATE TABLE inventory_item (
    product_id UUID PRIMARY KEY,
    seller_id VARCHAR(64) NOT NULL,
    available INT NOT NULL CHECK (available >= 0),
    reserved INT NOT NULL CHECK (reserved >= 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE stock_reservation (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES inventory_item (product_id),
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    requester_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX stock_reservation_expiry_idx ON stock_reservation (status, expires_at);

CREATE TABLE inventory_movement (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    reservation_id UUID,
    movement_type VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
