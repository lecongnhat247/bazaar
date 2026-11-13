-- M4: one fulfillment group per (order, seller). Mock/manual fulfillment only;
-- there is no shipping provider integration.

CREATE TABLE fulfillment (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES customer_order (id),
    seller_id VARCHAR(64) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    shipment_reference VARCHAR(40),
    packed_at TIMESTAMPTZ,
    shipped_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fulfillment_group UNIQUE (order_id, seller_id)
);

CREATE INDEX fulfillment_seller_idx ON fulfillment (seller_id, status);

CREATE TABLE fulfillment_event (
    id UUID PRIMARY KEY,
    fulfillment_id UUID NOT NULL REFERENCES fulfillment (id),
    from_status VARCHAR(10),
    to_status VARCHAR(10) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    note VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX fulfillment_event_idx ON fulfillment_event (fulfillment_id, created_at);
