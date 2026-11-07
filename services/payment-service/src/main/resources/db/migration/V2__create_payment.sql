CREATE TABLE payment (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    payer_id VARCHAR(64) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_idempotency_key_uq UNIQUE (idempotency_key)
);

CREATE UNIQUE INDEX payment_open_order_uq
    ON payment (order_id)
    WHERE status IN ('PENDING', 'SUCCEEDED');

CREATE TABLE payment_attempt (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payment (id),
    attempt_type VARCHAR(32) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX payment_attempt_payment_idx ON payment_attempt (payment_id, created_at);
