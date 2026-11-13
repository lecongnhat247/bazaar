-- M4: server-side coupons. The browser sends only a coupon code; the discount
-- is always calculated by the server and snapshotted on the order lines.

ALTER TABLE customer_order
    ADD COLUMN discount_total NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
    ADD COLUMN coupon_code VARCHAR(40);

ALTER TABLE order_item
    ADD COLUMN discount_amount NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    ADD CONSTRAINT order_item_discount_within_line CHECK (discount_amount <= line_total);

CREATE TABLE coupon (
    code VARCHAR(40) PRIMARY KEY,
    discount_type VARCHAR(10) NOT NULL CHECK (discount_type IN ('PERCENT', 'FIXED')),
    percent NUMERIC(5, 2),
    fixed_amount NUMERIC(19, 2),
    currency CHAR(3) NOT NULL,
    min_subtotal NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (min_subtotal >= 0),
    max_discount NUMERIC(19, 2) CHECK (max_discount IS NULL OR max_discount > 0),
    seller_id VARCHAR(64),
    funded_by VARCHAR(10) NOT NULL CHECK (funded_by IN ('PLATFORM', 'SELLER')),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    usage_limit INTEGER CHECK (usage_limit IS NULL OR usage_limit > 0),
    per_customer_limit INTEGER NOT NULL DEFAULT 1 CHECK (per_customer_limit > 0),
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT coupon_value_shape CHECK (
        (discount_type = 'PERCENT' AND percent IS NOT NULL AND percent > 0 AND percent <= 90 AND fixed_amount IS NULL)
        OR (discount_type = 'FIXED' AND fixed_amount IS NOT NULL AND fixed_amount > 0 AND percent IS NULL)),
    CONSTRAINT coupon_window CHECK (ends_at > starts_at),
    CONSTRAINT coupon_seller_funding CHECK (funded_by = 'PLATFORM' OR seller_id IS NOT NULL)
);

-- One redemption per order (UNIQUE order_id makes checkout replays harmless).
-- A redemption is RELEASED when the order never confirms, which frees the usage.
CREATE TABLE coupon_redemption (
    id UUID PRIMARY KEY,
    coupon_code VARCHAR(40) NOT NULL REFERENCES coupon (code),
    order_id UUID NOT NULL UNIQUE REFERENCES customer_order (id),
    customer_id VARCHAR(64) NOT NULL,
    discount_amount NUMERIC(19, 2) NOT NULL CHECK (discount_amount > 0),
    funded_by VARCHAR(10) NOT NULL CHECK (funded_by IN ('PLATFORM', 'SELLER')),
    status VARCHAR(10) NOT NULL CHECK (status IN ('APPLIED', 'RELEASED')),
    created_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ
);

CREATE INDEX coupon_redemption_coupon_idx ON coupon_redemption (coupon_code, status);
CREATE INDEX coupon_redemption_customer_idx ON coupon_redemption (coupon_code, customer_id, status);
