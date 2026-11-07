CREATE TABLE customer_order (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    currency CHAR(3) NOT NULL,
    total_amount NUMERIC(19, 2) NOT NULL CHECK (total_amount > 0),
    outcome VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    payment_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT customer_order_idempotency_key_uq UNIQUE (idempotency_key)
);

CREATE INDEX customer_order_customer_idx ON customer_order (customer_id, created_at DESC);

CREATE TABLE order_item (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES customer_order (id),
    product_id UUID NOT NULL,
    seller_id VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(160) NOT NULL,
    unit_price NUMERIC(19, 2) NOT NULL CHECK (unit_price > 0),
    quantity INT NOT NULL CHECK (quantity > 0),
    line_total NUMERIC(19, 2) NOT NULL CHECK (line_total > 0),
    reservation_id UUID NOT NULL,
    CONSTRAINT order_item_order_product_uq UNIQUE (order_id, product_id)
);

CREATE TABLE order_status_history (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES customer_order (id),
    status VARCHAR(32) NOT NULL,
    seq INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT order_status_history_seq_uq UNIQUE (order_id, seq)
);
